# Account Service

Owns every account balance. It holds, captures and releases money for the Transfer service, and
guarantees money is never overspent, even when many requests hit the same account at once
(Challenge 1).

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Flyway · springdoc OpenAPI

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the database).

```bash
# 0. Settings: copy .env.example to .env and set DB_PASSWORD (never commit .env)
cp .env.example .env

# 1. This service's own database (port 5434, db account_db, user account_user)
docker compose -f docker-compose.dev.yml up -d

# 2. The service, on port 8081 (or run AccountServiceApplication from IntelliJ)
mvn spring-boot:run

# 3. Check it
curl localhost:8081/health
scripts/smoke.sh
```

When the Transfer service runs too, stop the Account stub in `DevStubs` first (both use port 8081).

| What | Where |
| --- | --- |
| Health | `GET http://localhost:8081/health` |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI spec | `GET /v3/api-docs.yaml` (save to `docs/openapi/account-service.yaml` for the report) |

Tests: `mvn verify`. No database needed; tests use in-memory H2 in PostgreSQL mode.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | 8081 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (password has no default; set it in `.env`) | `jdbc:postgresql://localhost:5434/account_db`, `account_user`, none (from `.env`) | This service's own database |
| `DB_POOL_SIZE` | 30 | Database connections (enough for the 25-request race) |
| `HOLD_STRATEGY` | atomic | `naive` is deliberately broken; only for the Challenge 1 "before" demo |
| `NAIVE_DELAY_MS` | 50 | Pause between read and write in naive mode |
| `LOG_FORMAT` | logstash | JSON logs |

### Demo accounts (created by `V2__seed_demo_accounts.sql`)

| Account | Currency | Balance | Status |
| --- | --- | --- | --- |
| ACC-1001 | INR | 10,000 | ACTIVE |
| ACC-2002 | INR | 5,000 | ACTIVE |
| ACC-EMPTY | INR | 0 | ACTIVE |
| ACC-FROZEN | INR | 1,000 | FROZEN |
| ACC-USD-1 | USD | 500 | ACTIVE (for the currency-mismatch demo) |

## How money is tracked

Each account has two amounts:

- **balance**: money really in the account.
- **available**: balance minus money on hold, i.e. what can still be spent.

| Operation | Source account | Destination account |
| --- | --- | --- |
| Hold (transfer step 2) | available − amount | |
| Capture (step 4) | balance − amount | balance + amount, available + amount |
| Release (undo) | available + amount | |

The database itself refuses `balance < 0`, `available < 0` and `available > balance` (CHECK constraints),
as a last line of defence.

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/api/v1/holds` | 201 new · 200 already held | 404 `ACCOUNT_NOT_FOUND` · 409 `INSUFFICIENT_FUNDS` · 422 `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`, `INVALID_AMOUNT`, `HOLD_MISMATCH` |
| POST | `/api/v1/holds/{transferId}/capture` | 200 (again 200 if repeated) | 404 `HOLD_NOT_FOUND` · 409 `HOLD_NOT_ACTIVE` |
| POST | `/api/v1/holds/{transferId}/release` | 200 (again 200 if repeated) | 404 `HOLD_NOT_FOUND` · 409 `HOLD_ALREADY_CAPTURED` |
| GET | `/api/v1/holds/{transferId}` | 200 | 404 |
| POST | `/api/v1/accounts` | 201 | 400 `VALIDATION_FAILED` · 409 `ACCOUNT_ALREADY_EXISTS` |
| GET | `/api/v1/accounts`, `/api/v1/accounts/{id}`, `/api/v1/accounts/{id}/holds` | 200 | 404 |
| PATCH | `/api/v1/accounts/{id}/status` `{"status":"FROZEN"}` | 200 | 409 `INVALID_STATUS_CHANGE`, `ACCOUNT_NOT_EMPTY` |
| GET | `/health` | 200 UP | 503 |

The three `/holds` calls are exactly the contract the Transfer service uses. All errors use the team's
shared format `{"error":{"code","message","requestId","details"}}`, and every request carries `X-Request-ID`.

## Challenge 1: concurrency

**The problem.** The obvious code is "read the available balance, check it in Java, then save the new value".
Two requests for the last ₹100 can both read ₹100, both pass the check, and both save ₹0: ₹200 promised from
₹100. Each save looks valid on its own, so nothing catches it.

**The fix.** The check and the change happen in **one SQL statement**:

```sql
UPDATE accounts SET available = available - :amount, version = version + 1
WHERE id = :id AND available >= :amount AND status = 'ACTIVE';
```

The database holds the row lock while it checks and changes the row, so concurrent requests run one after
another, and each one re-checks `available >= amount` against the latest value. If 0 rows change, there was
not enough money and the API answers 409 `INSUFFICIENT_FUNDS`. The hold row was inserted in the same
transaction, so it is rolled back too: a refused request leaves nothing behind.

**Alternatives considered** (for the report):

| Option | Why not chosen |
| --- | --- |
| `SELECT ... FOR UPDATE`, then check in Java, then update | Also correct, but two round trips while holding the lock; the single statement is simpler |
| Optimistic locking (`@Version`) only | Correct, but under contention most requests fail with a conflict and must retry |
| `synchronized` in Java | Only works for one instance of the service; breaks as soon as there are two |

**Evidence.**

```bash
scripts/challenge1-concurrency.sh                  # 25 holds of 100 against 500
```

Expected: 5 succeed, 20 get 409, and the account ends with balance 500, available 0, onHold 500, with 5 holds.
Output goes to `evidence/challenge-1-concurrency/`.

To show the bug as well, restart with `HOLD_STRATEGY=naive` and run `LABEL=naive scripts/challenge1-concurrency.sh`.
More than 5 holds succeed, and available no longer matches the holds. Tested directly in PostgreSQL with
25 parallel requests: atomic gave 5 successes and available 0; naive gave 25 "successes" for ₹2,500 against
₹500, and still showed ₹300 available.

## Other safety rules

- **Safe to repeat.** A hold is keyed by `transferId` (primary key). Repeating "place hold" returns the
  existing hold with 200; ten identical requests at the same moment reserve the money once. Repeating capture
  or release returns 200 and changes nothing.
- **Capture and release never both happen.** Both lock the hold row (`SELECT ... FOR UPDATE`), so they run one
  after the other, and a captured hold cannot be released (409) or the reverse.
- **No deadlock between opposite transfers.** Capture always updates the two accounts in alphabetical order,
  so A→B and B→A captured together cannot block each other.

## Business rules: where they live and where they are tested

| Rule | Code | Test |
| --- | --- | --- |
| BR2 available balance never negative | `AccountJpaRepository.reserveIfAvailable` + DB CHECK | `AccountApiIntegrationTest.challenge1_*`, `BR2_*` |
| BR4 accounts ACTIVE, different, same currency | `domain/HoldRules` | `HoldRulesTest`, `AccountApiIntegrationTest.BR4_*` |
| A1 hold is captured or released, never both | `domain/HoldStatus`, `Hold` | `HoldStatusTest`, `AccountApiIntegrationTest.hold*` |
| A2 only an empty account can be closed; CLOSED is final | `Account.changeStatus` | `AccountStatusTest`, `AccountApiIntegrationTest.A2_*` |

## Code layout

```
src/main/java/com/moneytransfer/account/
  domain/          Account, Hold, statuses, HoldRules (plain Java, unit-tested)
  application/     HoldService (repeats), HoldTransactions (hold, capture, release), AccountService
  persistence/     Repositories with the atomic UPDATE statements; NaiveReservation (demo only)
  web/             Controllers, request-ID filter, error format, DTOs (shared with Transfer)
  config/          Properties and Swagger info
src/main/resources/db/migration/   Tables and demo accounts
scripts/                           smoke test and Challenge 1 evidence
```

## Known limitations

- Holds do not expire. If the Transfer service disappeared mid-saga for good, money would stay on hold until
  its recovery job releases it. A timed expiry (Challenge 3B) would add a second safety net.
- Demo accounts are created by a migration, so they also exist in any real deployment of this schema.
- There are no deposit or withdrawal endpoints; money only enters through the opening balance.
