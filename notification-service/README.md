# Notification Service

Tells the customer that their transfer completed (by SMS or email). The Transfer service calls it as the last
step of every successful transfer.

The SMS/email provider is **simulated**: it logs the message instead of sending it, and it can be made slow or
made to fail on purpose. That makes this service the system's **unreliable dependency** (condition e), and it
shows two things:

- **N1: never sent twice.** Transfer retries on failures and timeouts, so the same transfer can be notified
  several times, even at the same moment. The customer gets one message.
- **N3: a failure never breaks a transfer.** A failed send is recorded and answered with 503; Transfer retries
  in the background, and the transfer stays COMPLETED either way. Every attempt is kept as evidence.

Spring Boot 3.5 · Java 21 · PostgreSQL 16 · Flyway · springdoc OpenAPI

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the database).

```bash
# 0. Settings: copy .env.example to .env and set DB_PASSWORD (never commit .env)
cp .env.example .env

# 1. This service's own database (port 5437, db notification_db, user notification_user)
docker compose -f docker-compose.dev.yml up -d

# 2. The service, on port 8085 (or run NotificationServiceApplication from IntelliJ)
mvn spring-boot:run

# 3. Check it
curl localhost:8085/health
scripts/smoke.sh
```

With all five services running, Transfer's stand-ins (`dev-stubs/DevStubs.java`) are no longer needed. To run
only some, skip the real ones: `java dev-stubs/DevStubs.java --skip account,ledger,limits,notification`.

| What | Where |
| --- | --- |
| Health | `GET http://localhost:8085/health` |
| Swagger UI | http://localhost:8085/swagger-ui.html |
| OpenAPI spec | `GET /v3/api-docs.yaml` (save to `docs/openapi/notification-service.yaml` for the report) |

Tests: `mvn verify`. No database needed; tests use in-memory H2 in PostgreSQL mode.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | 8085 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (password has no default; set it in `.env`) | `jdbc:postgresql://localhost:5437/notification_db`, `notification_user`, none (from `.env`) | This service's own database |
| `NOTIFY_DELAY_MS` | 0 | Provider waits this long per send. Above 3000, Transfer times out and retries |
| `NOTIFY_FAIL_RATE` | 0.0 | Share of sends that fail at random (0.0 to 1.0) |
| `NOTIFY_FAIL_NEXT` | 0 | The next N sends fail, then sending works again (repeatable demos) |
| `ADMIN_ENDPOINTS_ENABLED` | true | Allows `PUT /api/v1/admin/provider`; set false outside demos |
| `LOG_FORMAT` | logstash | JSON logs |

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/api/v1/notifications` | 202 sent (also 202 for a repeat; nothing is sent twice) | 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST` · 422 `NOTIFICATION_MISMATCH`, `INVALID_TEMPLATE`, `INVALID_MESSAGE` · 503 `PROVIDER_DOWN` |
| GET | `/api/v1/notifications?transferId=...` | 200: list, with every attempt (empty list if none) | 400 `MISSING_PARAMETER` |
| GET | `/api/v1/notifications?accountId=...&limit=50` | 200: newest first | 400 `MISSING_PARAMETER` |
| GET | `/api/v1/admin/provider` | 200: current provider settings | |
| PUT | `/api/v1/admin/provider` `{"delayMs": 0, "failRate": 0.0, "failNext": 2}` | 200 | 422 `INVALID_SETTING` |
| GET | `/health` | 200 UP | 503 |

Notify request (exactly what the Transfer service sends after a transfer completes):

```json
{"transferId": "3f6c2a1e-7b8d-4c1a-9e2f-5d4b3a2c1e0f", "accountId": "ACC-1001", "channel": "SMS",
 "template": "TRANSFER_COMPLETED", "message": "You sent 1500.00 INR to ACC-2002. Ref 3f6c2a1e-..."}
```

`channel` is `SMS` or `EMAIL`. `template` is upper case (`TRANSFER_COMPLETED`); one transfer can have one
notification per template. 202 response:

```json
{"notificationId": "N-000001", "transferId": "3f6c2a1e-...", "accountId": "ACC-1001", "channel": "SMS",
 "template": "TRANSFER_COMPLETED", "status": "SENT", "attempts": 1,
 "providerMessageId": "SMS-1a2b3c4d", "sentAt": "2026-10-30T10:15:31Z"}
```

503 when the provider failed this time (retrying the same request may succeed):

```json
{"error": {"code": "PROVIDER_DOWN", "message": "the notification provider failed (attempt 1): simulated provider error (failRate 1.0)",
           "requestId": "...", "details": [{"notificationId": "N-000002", "attempts": 1}]}}
```

`GET /api/v1/notifications?transferId=...` returns each notification with its message, `lastError`, and a
`history` of every provider attempt: `[{"attemptNo": 1, "outcome": "FAILED", "error": "...", "durationMs": 2,
"at": "..."}, {"attemptNo": 2, "outcome": "SENT", ...}]`.

All errors use the team format `{"error":{"code","message","requestId","details"}}`, and every request carries
`X-Request-ID`.

## How "never sent twice" works

1. **Find or create the notification row.** It is unique per `(transfer_id, template)`. If two requests try to
   create it at the same moment, the database refuses the second insert and that request uses the first one's
   row.
2. **Same details?** A repeat must have the same account, channel and message, otherwise 422
   `NOTIFICATION_MISMATCH`.
3. **In one transaction, holding the row lock** (`SELECT ... FOR UPDATE`):
   - Already SENT → do nothing, answer 202 (a repeat).
   - Otherwise call the provider, then save the outcome and an attempt row: SENT, or FAILED with the error.
     A failure is a normal outcome here, so it is saved, not rolled back. The service then answers 503.

Other requests for the same transfer wait at the lock while the provider is called. When they get the lock, they
see SENT and send nothing.

Status rules: PENDING → SENT or FAILED; FAILED → SENT or FAILED (a retry); SENT is final.

**Verified on PostgreSQL 16:** 10 identical requests at the same moment, with a slow provider:
1 sent, 9 found it already sent; one notification row, one provider call. `scripts/parallel-notify.sh`
reproduces this through the API and saves the output under `evidence/`.

## Demo: the provider fails (condition e)

```bash
scripts/provider.sh fail-next 2     # the next two sends fail
# make a transfer in the Transfer service, then:
curl "localhost:8085/api/v1/notifications?transferId=<id>"   # history: FAILED, FAILED, SENT
scripts/provider.sh ok
```

Transfer is COMPLETED immediately; its notification status moves to SENT after its background retries.
Other switches: `scripts/provider.sh down` (every send fails: the transfer is still COMPLETED, its notification
FAILED), `flaky 0.5`, and `slow 5000` (Transfer times out after 3 s and retries; the repeat is not sent twice).

## Business rules: where they live and where they are tested

| Rule | Code | Test |
| --- | --- | --- |
| N1 never sent twice, even in parallel | UNIQUE `(transfer_id, template)`, `NotificationService.findOrCreate`, row lock in `DeliveryTransactions.deliver` | `NotificationApiIntegrationTest.N1_*`, `notify_is202Sent_andARepeat*` |
| N1 a repeat must match | `Notification.sameRequest` | `NotificationTest`, `sameTransferWithADifferentMessage_is422` |
| N3 failures recorded, retry can succeed | `Notification.recordFailure`, `NotificationAttempt`, `NotificationStatus` | `NotificationStatusTest`, `N3_providerFailsTwice_*`, `providerAlwaysFailing_*` |
| Valid template and message | `NotificationRules.check` + DTO validation | `NotificationRulesTest`, `invalidFields_are400_*` |
| Simulated provider settings | `SimulatedProvider` | `SimulatedProviderTest`, `invalidProviderSetting_is422` |

## Code layout

```
src/main/java/com/moneytransfer/notification/
  domain/          Notification, NotificationAttempt (entities), NotificationStatus, NotificationRules, Channel
  application/     NotificationService (create, repeats, reads), DeliveryTransactions (one locked attempt)
  provider/        SimulatedProvider (logs the message; delay, fail rate, fail next)
  persistence/     JPA repositories (incl. the row lock)
  web/             NotificationController, AdminController, request-ID filter, error format, DTOs
  config/          Properties, provider bean and Swagger info
src/main/resources/db/migration/   notifications and notification_attempts tables
scripts/                           smoke test, provider switches, parallel-notify evidence
```

## Known limitations

- **The provider is simulated.** Nothing is really sent; the message is written to the log.
- **A request waits for the provider.** The row lock is held while the provider is called, so a very slow
  provider makes repeats for the same transfer wait too. Transfer times out after 3 s and retries, and Transfer
  already sends notifications in the background, so a transfer is never held up. A real system would put the
  send on a queue.
- **No retry of its own.** If every one of Transfer's retries fails, the notification stays FAILED (visible
  through GET). A scheduled re-send of FAILED notifications would close this.
- **Provider timeout.** If the real provider sent the message but the answer was lost, a retry would send it
  again. Real providers solve this with their own idempotency key (we would pass the notificationId).
- The parallel integration test relies on H2's row locking behaving like PostgreSQL's (the same SQL was verified
  on PostgreSQL).
- The admin and read endpoints have no authentication (out of scope for the assignment).
