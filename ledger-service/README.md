# Ledger Service

The bank's permanent record book. For every completed transfer it records one balanced double-entry
journal (debit the source, credit the destination). The ledger is **append-only**: nothing is ever
edited or deleted. When the Transfer saga has to undo a transfer, the Ledger posts a **reversal**, the
same lines with debit and credit swapped, so the two journals net to zero.

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Flyway · springdoc OpenAPI

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the database).

```bash
# 0. Settings: copy .env.example to .env and set DB_PASSWORD (never commit .env)
cp .env.example .env

# 1. This service's own database (port 5435, db ledger_db, user ledger_user)
docker compose -f docker-compose.dev.yml up -d

# 2. The service, on port 8083 (or run LedgerServiceApplication from IntelliJ)
mvn spring-boot:run

# 3. Check it
curl localhost:8083/health
scripts/smoke.sh
```

To run it with the Transfer service, start Transfer's stand-ins without the Ledger stub:
`java dev-stubs/DevStubs.java --skip account,ledger` (when Account runs too).

| What | Where |
| --- | --- |
| Health | `GET http://localhost:8083/health` |
| Swagger UI | http://localhost:8083/swagger-ui.html |
| OpenAPI spec | `GET /v3/api-docs.yaml` (save to `docs/openapi/ledger-service.yaml` for the report) |

Tests: `mvn verify`. No database needed; tests use in-memory H2 in PostgreSQL mode.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | 8083 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (password has no default; set it in `.env`) | `jdbc:postgresql://localhost:5435/ledger_db`, `ledger_user`, none (from `.env`) | This service's own database |
| `LEDGER_FAIL` | false | `true`: `POST /journals` answers 503 (Challenge 3C demo). Reversals still work. |
| `LEDGER_DELAY_MS` | 0 | Wait this long after saving a new journal before answering (retry demo) |
| `ADMIN_ENDPOINTS_ENABLED` | true | Allow switching the two above at runtime (`scripts/fault.sh`) |
| `LOG_FORMAT` | logstash | JSON logs |

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/api/v1/journals` | 201 new · 200 already recorded | 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST` · 422 `UNBALANCED_JOURNAL`, `INVALID_ENTRY`, `JOURNAL_MISMATCH` · 503 `LEDGER_FORCED_FAILURE` |
| POST | `/api/v1/journals/{transferId}/reversal` | 201 new · 200 already reversed | 404 `JOURNAL_NOT_FOUND` |
| GET | `/api/v1/journals/{transferId}` | 200, list: the journal, then its reversal if any | 404 `JOURNAL_NOT_FOUND` |
| GET | `/api/v1/journals?accountId=ACC-1001&limit=100` | 200, the account's lines, newest first | 400 `MISSING_PARAMETER` |
| GET / PUT | `/api/v1/admin/faults` `{"failPosts": true, "postDelayMs": 0}` | 200 | (demo only) |
| GET | `/health` | 200 UP | 503 |

Post request (exactly what the Transfer service sends at step 3):

```json
{"transferId": "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f", "currency": "INR",
 "entries": [{"accountId": "ACC-1001", "direction": "DEBIT",  "amount": 1500.00},
             {"accountId": "ACC-2002", "direction": "CREDIT", "amount": 1500.00}]}
```

Journal response:

```json
{"journalId": "J-000123", "transferId": "3f6c2a1e-...", "type": "TRANSFER", "currency": "INR",
 "reverses": null, "reversedBy": null, "postedAt": "2026-10-30T10:15:31Z",
 "entries": [{"accountId": "ACC-1001", "direction": "DEBIT",  "amount": 1500.00},
             {"accountId": "ACC-2002", "direction": "CREDIT", "amount": 1500.00}]}
```

A reversal has `"type": "REVERSAL"`, `"reverses": "J-000123"` and the directions swapped; the original then
shows `"reversedBy"` with the reversal's id. All errors use the team format
`{"error":{"code","message","requestId","details"}}`, and every request carries `X-Request-ID`.

## How the rules are enforced (twice)

| Rule | In Java | In PostgreSQL (defence in depth) |
| --- | --- | --- |
| **BR5** debits = credits, both sides present, amounts > 0 with ≤ 2 decimals | `JournalRules.check` before saving | Deferred constraint trigger checks every journal's totals at COMMIT; CHECK `amount > 0` |
| **BR5** one journal per transfer | `JournalService` returns the existing one | UNIQUE `(transfer_id, type)` |
| **L1** append-only; corrections are reversals | Entities are `@Immutable` with no setters; `Journal.reversal()` builds the mirror image | Triggers refuse every UPDATE, DELETE and TRUNCATE |
| **L2** at most one reversal per transfer, and only of a TRANSFER journal | `JournalService.reverse` | UNIQUE `(transfer_id, type)` and UNIQUE `reverses_journal_id` |

The PostgreSQL triggers live in `db/migration/postgresql/` and run only on PostgreSQL (Flyway's `{vendor}`
folder). They were tested on PostgreSQL 16: an unbalanced journal is refused at commit, a second journal
for a transfer is refused, and UPDATE, DELETE and TRUNCATE all fail with "ledger is append-only".

## Safe to repeat

The Transfer service retries when it gets no answer, so the same post can arrive twice, even at the same moment.

- **Sequential repeat:** the existing journal is returned with 200 if the lines match (order and number
  formatting don't matter), or 422 `JOURNAL_MISMATCH` if they differ.
- **Simultaneous repeat:** the UNIQUE `(transfer_id, type)` constraint lets one INSERT win; the other fails,
  `JournalService` catches it outside the failed transaction, finds the winner and returns it with 200.
- **Reversal:** returns the existing reversal with 200; reversing a transfer with no journal is 404, which the
  Transfer saga treats as "nothing to undo".

Evidence: `scripts/parallel-post.sh` fires 10 identical posts at once (`N=20` for more) and shows one 201,
the rest 200, and a single journal.

## Demo switches

```bash
scripts/fault.sh on          # POST /journals answers 503 LEDGER_FORCED_FAILURE
scripts/fault.sh slow 5000   # save the journal, then wait 5 s before answering
scripts/fault.sh off
```

- **Challenge 3C (saga):** with `on`, the Transfer saga fails at step 3 and undoes steps 2 and 1. Only posts
  fail; reversals keep working, so the undo completes and the transfer ends FAILED. The Transfer project's
  `scripts/challenge3c-saga-failure.sh` switches this on and off for you.
- **Retries don't duplicate:** with `slow 5000` (above Transfer's 3 s read timeout), Transfer times out, retries
  the post, and gets the already-saved journal back with 200. The transfer completes with exactly one journal.

## Business rules: where they live and where they are tested

| Rule | Code | Test |
| --- | --- | --- |
| BR5 balanced journal | `domain/JournalRules` (+ PostgreSQL trigger) | `JournalRulesTest.BR5_*`, `LedgerApiIntegrationTest.BR5_*` |
| BR5 one journal per transfer | `JournalService.post` + UNIQUE constraint | `postingAJournal_is201_andARepeatIs200WithTheSameJournal`, `tenIdenticalPostsAtTheSameMoment_recordOneJournal` |
| L1 append-only; corrections by reversal | `Journal.reversal`, `@Immutable` (+ PostgreSQL triggers) | `JournalTest.L1_*`, `reversal_is201_swapsDirections_andARepeatIs200` |
| L2 one reversal per transfer | `JournalService.reverse` + UNIQUE constraints | `reversal_is201_swapsDirections_andARepeatIs200`, `JournalTest.aReversalCannotBeReversed` |

## Code layout

```
src/main/java/com/moneytransfer/ledger/
  domain/          Journal, JournalEntry (immutable entities), JournalRules (BR5), Direction, EntryLine
  application/     JournalService (repeats), JournalTransactions (the two writes), FaultInjection (demo)
  persistence/     Repositories
  web/             JournalController, AdminController, request-ID filter, error format, DTOs
  config/          Properties and Swagger info
src/main/resources/db/migration/
  common/          Tables (every database)
  postgresql/      Append-only and balance triggers (PostgreSQL only)
scripts/           smoke test, parallel-post evidence, fault switch
```

## Known limitations

- **A very late post after the saga gave up.** If a post were delayed *before* saving for longer than
  Transfer's retries, Transfer could undo the transfer (reversal → 404) and the late post could then save a
  journal nobody reverses. The demo delay is applied *after* saving to avoid this; a real fix is to record a
  "voided" marker on a 404 reversal and refuse later posts for that transfer.
- The Ledger trusts the account IDs it is given; it does not check them with the Account service (by design:
  no cross-service calls on the write path).
- Journal ids come from a database sequence, so gaps appear when a post is refused; the ids are not a
  gap-free audit number.
- The admin fault endpoints have no authentication; set `ADMIN_ENDPOINTS_ENABLED=false` outside demos.
