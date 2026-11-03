# Limits / Fraud Service

Decides whether an account may send an amount, and reserves that usage so two transfers can never
both squeeze under a limit. It enforces three rules:

- **BR3:** daily limit per account (default ₹2,00,000 per UTC day, pending transfers included)
- **BR1:** per-transfer cap (default ₹1,00,000; Transfer checks it too)
- **F1 (fraud):** at most 10 transfers per account within 60 seconds, since a burst is a common sign
  of a compromised account

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Flyway · springdoc OpenAPI

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the database).

```bash
# 0. Settings: copy .env.example to .env and set DB_PASSWORD (never commit .env)
cp .env.example .env

# 1. This service's own database (port 5436, db limits_db, user limits_user)
docker compose -f docker-compose.dev.yml up -d

# 2. The service, on port 8084 (or run LimitsServiceApplication from IntelliJ)
mvn spring-boot:run

# 3. Check it
curl localhost:8084/health
scripts/smoke.sh
```

To run it with the Transfer service, start Transfer's stand-ins without this one:
`java dev-stubs/DevStubs.java --skip account,ledger,limits` (when Account and Ledger run too).

| What | Where |
| --- | --- |
| Health | `GET http://localhost:8084/health` |
| Swagger UI | http://localhost:8084/swagger-ui.html |
| OpenAPI spec | `GET /v3/api-docs.yaml` (save to `docs/openapi/limits-service.yaml` for the report) |

Tests: `mvn verify`. No database needed; tests use in-memory H2 in PostgreSQL mode.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | 8084 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (password has no default; set it in `.env`) | `jdbc:postgresql://localhost:5436/limits_db`, `limits_user`, none (from `.env`) | This service's own database |
| `DAILY_LIMIT` | 200000.00 | Default BR3 limit |
| `PER_TRANSFER_LIMIT` | 100000.00 | Default BR1 cap |
| `VELOCITY_MAX_TRANSFERS` | 10 | F1: most transfers per account in the window; `0` switches it off |
| `VELOCITY_WINDOW_SECONDS` | 60 | F1 window |
| `LOG_FORMAT` | logstash | JSON logs |

**Demo account:** migration `V2__demo_account_limits.sql` gives ACC-2002 a personal limit of ₹2,000 per day
(₹1,500 per transfer). ACC-2002 holds ₹5,000 in the Account service, so BR3 can be shown end to end: a transfer
of 1,500 from ACC-2002 succeeds, and a second one the same day is refused with `LIMIT_EXCEEDED`.

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/api/v1/limit-reservations` | 201 new · 200 already reserved | 400 `VALIDATION_FAILED` · 409 `RESERVATION_RELEASED` · 422 `LIMIT_EXCEEDED`, `VELOCITY_LIMIT`, `INVALID_AMOUNT`, `RESERVATION_MISMATCH` |
| DELETE | `/api/v1/limit-reservations/{transferId}` | 204 (also when already released) | 404 `RESERVATION_NOT_FOUND` |
| GET | `/api/v1/limit-reservations/{transferId}` | 200 | 404 |
| GET | `/api/v1/limits/{accountId}` | 200: limits, today's usage, recent transfers | |
| PUT | `/api/v1/limits/{accountId}` `{"dailyLimit": 50000, "perTransferLimit": 25000}` | 200 | 422 `INVALID_LIMIT` |
| DELETE | `/api/v1/limits/{accountId}` | 204, back to defaults | |
| GET | `/health` | 200 UP | 503 |

Reserve request (exactly what the Transfer service sends at step 1):

```json
{"transferId": "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f", "accountId": "ACC-1001", "amount": 1500.00, "currency": "INR"}
```

Reservation response:

```json
{"transferId": "3f6c2a1e-...", "accountId": "ACC-1001", "amount": 1500.00, "currency": "INR",
 "status": "RESERVED", "day": "2026-10-30", "usedToday": 1500.00, "dailyLimit": 200000.00,
 "remaining": 198500.00, "createdAt": "2026-10-30T10:15:30Z", "releasedAt": null}
```

`GET /api/v1/limits/ACC-1001`:

```json
{"accountId": "ACC-1001", "day": "2026-10-30", "dailyLimit": 200000.00, "perTransferLimit": 100000.00,
 "personal": false, "usedToday": 1500.00, "remaining": 198500.00,
 "recentTransfers": 1, "velocityMaxTransfers": 10, "velocityWindowSeconds": 60}
```

An account with no transfers today simply shows `usedToday` 0; Limits does not need to know which accounts
exist. All errors use the team format `{"error":{"code","message","requestId","details"}}`, and every request
carries `X-Request-ID`.

## How a reservation is made safe

The daily limit is a contended resource, exactly like an account balance: two ₹1,50,000 transfers checked at
the same moment against a ₹2,00,000 limit must not both pass.

1. **Make sure the account has a usage row for today** (`daily_usage`), in its own small step. If another
   request created it a moment earlier, the insert fails harmlessly.
2. **In one transaction:**
   - INSERT the reservation. Its primary key is the `transferId`, so a second reservation for the same
     transfer is impossible.
   - Add the amount with **one conditional UPDATE**:
     ```sql
     UPDATE daily_usage SET used = used + :amount
     WHERE account_id = :account AND usage_day = :today AND used + :amount <= :dailyLimit
     ```
     The database locks the row, and concurrent reservations for the same account wait and re-check the
     condition against the latest value. 0 rows changed → 422 `LIMIT_EXCEEDED`.
   - **F1:** count the account's reservations in the last 60 s. This runs while the UPDATE still holds the
     account's row lock, so concurrent reservations are counted one after another. More than 10 → 422
     `VELOCITY_LIMIT`.
   - Any refusal rolls back the whole transaction, so a refused request leaves nothing behind.

**Verified on PostgreSQL 16:**

| Race | Result |
| --- | --- |
| 25 parallel reservations of ₹100 against a daily limit of ₹500 | Exactly 5 reserved, 20 refused, usage ₹500 |
| 20 parallel reservations with a velocity limit of 5 | Exactly 5 committed, 15 refused, usage ₹50 |

`scripts/concurrency.sh` and `scripts/fraud-velocity.sh` reproduce both through the API and save the output
under `evidence/`.

## Safe to repeat

- **Reserve again, same details:** 200 with the existing reservation; the amount is counted once, even for 10
  identical requests at the same moment.
- **Same transferId, different details:** 422 `RESERVATION_MISMATCH`.
- **Release again:** 204, nothing changes. Releasing an unknown transfer is 404, which the Transfer saga treats
  as "nothing to undo".
- **Reserve a transfer that was already released:** 409 `RESERVATION_RELEASED`. A transfer that the saga undid
  can't be brought back to life by a late or repeated request.

A release gives the amount back to the day it was **taken from**, even after midnight.

## Business rules: where they live and where they are tested

| Rule | Code | Test |
| --- | --- | --- |
| BR3 daily limit, never over-allocated | `UsageRepository.reserveIfWithinLimit` + CHECK `used >= 0` | `LimitsApiIntegrationTest.BR3_*` (incl. 25 parallel) |
| BR1 per-transfer cap | `LimitPolicy.checkPerTransfer` | `LimitPolicyTest.BR1_*`, `BR1_amountAboveThePerTransferLimit_is422` |
| F1 velocity (fraud) | `LimitRules.checkVelocity`, counted in `ReservationTransactions` | `LimitRulesTest.F1_*`, `F1_theSeventhTransferWithinAMinute_isRefusedAsVelocityLimit` |
| LM1 released at most once, back to its own day | `Reservation.release`, `ReservationStatus` | `ReservationTest.LM1_*`, `release_is204_*`, `reservingAgainAfterRelease_is409_*` |

## Code layout

```
src/main/java/com/moneytransfer/limits/
  domain/          Reservation, AccountLimit (entities), LimitPolicy (BR1), LimitRules (F1, amounts)
  application/     ReservationService (repeats), ReservationTransactions (reserve, release), LimitPolicyService
  persistence/     UsageRepository (the atomic SQL), JPA repositories
  web/             ReservationController, LimitController, request-ID filter, error format, DTOs
  config/          Properties, Clock (UTC) and Swagger info
src/main/resources/db/migration/   Tables and the ACC-2002 demo limit
scripts/                           smoke test, concurrency and fraud-velocity evidence
```

## Known limitations

- **Late reserve after the saga gave up.** If a reserve were delayed past all of Transfer's retries, Transfer
  could undo the transfer (release → 404) and the late reserve could then count against the limit until the
  day ends. A "voided" marker on a 404 release would close this.
- **Velocity across midnight.** Reservations queue on the account's row for the *current* day, so two requests
  either side of midnight are not serialised for the velocity count. The window is 60 s; the effect is at most
  one extra transfer.
- **One currency per account.** Limits are applied in the account's own currency; there is no conversion.
- The two parallel integration tests rely on H2's row locking behaving like PostgreSQL's (the same SQL was
  verified on PostgreSQL). If they fail only on H2, run them against PostgreSQL with Testcontainers.
- The limit-management endpoints have no authentication (out of scope for the assignment).
