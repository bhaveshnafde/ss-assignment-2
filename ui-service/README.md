# UI Service (demo dashboard)

One web page that drives the five services and shows what each one did. It is meant for the demo and for the
report's screenshots: every button makes real REST calls, and the page checks the results against the expected
outcome and shows ✓ or ✗.

Spring Boot 3.5 · Java 21 · plain HTML, CSS and JavaScript (no build step, nothing loaded from the internet)
· **no database**

## Run it

Start the five services first (each with its own database), then:

```bash
mvn spring-boot:run          # or run UiServiceApplication from IntelliJ
```

Open **http://localhost:8080**. The coloured dots at the top show which services are up.

| Service | Default URL | Override with |
| --- | --- | --- |
| Transfer | http://localhost:8082 | `TRANSFER_SERVICE_URL` |
| Account | http://localhost:8081 | `ACCOUNT_SERVICE_URL` |
| Ledger | http://localhost:8083 | `LEDGER_SERVICE_URL` |
| Limits | http://localhost:8084 | `LIMITS_SERVICE_URL` |
| Notification | http://localhost:8085 | `NOTIFICATION_SERVICE_URL` |

Other settings: `PORT` (8080), `UI_CONNECT_TIMEOUT_MS` (1000), `UI_REQUEST_TIMEOUT_MS` (60000).

The Ledger and Notification services must run with `ADMIN_ENDPOINTS_ENABLED=true` (the default), because the
demo flips their fault switches.

Tests: `mvn verify` (no other service needs to be running; the tests use small fake services).

## What each tab shows

| Tab | What it does | What you should see |
| --- | --- | --- |
| Overview | Health of every service and its database; all accounts with balance, available and on hold; open, freeze or unfreeze accounts; **Details** shows one account in all five services | Seed accounts ACC-1001, ACC-2002, ACC-EMPTY, ACC-FROZEN, ACC-USD-1 |
| Make a transfer | Sends `POST /api/v1/transfers` with an Idempotency-Key, then traces the transfer through all five services: the saga steps, the limit reservation, the hold, the ledger journal and the SMS | COMPLETED, hold CAPTURED, a balanced journal, SMS SENT. **Send again with the same key** shows `Idempotent-Replayed: true` |
| Challenge 1 · Concurrency | Opens a fresh account with ₹500 and fires 25 holds of ₹100 **at the same instant** (or 25 reservations against a ₹500 daily limit) | Exactly 5 succeed, 20 get 409 `INSUFFICIENT_FUNDS`, available ends at ₹0, never negative |
| Challenge 2 · Idempotency | Four scenarios: a retry with the same key; 10 identical requests at once; the same key with a different amount; no key | Money moves once; one transferId for 10 requests; 422 `IDEMPOTENCY_KEY_REUSED`; 400 `MISSING_IDEMPOTENCY_KEY` |
| Challenge 3C · Saga | Turns the Ledger failure on, sends ₹300, turns it off again, then checks every service | 503 `LEDGER_UNAVAILABLE`; transfer FAILED; undo in order hold → limit; hold and reservation RELEASED; balance and daily usage back where they started |
| Slow / failing dependency | Switches the SMS provider (normal, next 2 fail, half fail, all fail, slow) and follows one transfer's SMS live | Transfer COMPLETED at once whatever the provider does; with "next 2 fail" the attempts are FAILED, FAILED, SENT |
| Business rules | 14 requests, each breaking one rule (BR1 to BR5, F1, A2, the idempotency key), with the expected status and code. **Run all** | 14 ✓ |
| Request log | Every call the page made: service, method, path, status, time and `X-Request-ID`. Click a row for the request and answer bodies | The same `X-Request-ID` appears in the services' JSON logs |

The demos that change data open fresh accounts named `UI-…`, so they can be repeated and never disturb the seed
accounts. The Overview hides them by default.

**Show the broken version of Challenge 1:** start the Account service with `HOLD_STRATEGY=naive` and run the
tab again. Far more than 5 holds succeed, and the page says it was over-allocated.

**The fraud rule F1** (default 10 transfers per account per minute) also applies to demo transfers. If you click
**Send transfer** more than 10 times in a minute from the same account, the 11th is refused with
`VELOCITY_LIMIT`. That is the rule working, not a bug.

## How it works

```
browser ──► UI service :8080 ──► transfer :8082, account :8081, ledger :8083, limits :8084, notification :8085
            /proxy/<service>/<path>     one call, answered exactly as the service answered
            /api/ui/burst               the same call N times, released at the same instant
```

- **Why a forwarder.** A page served from port 8080 may not call ports 8081 to 8085 directly (the browser blocks
  it without CORS headers, which the services do not send). So the page calls `/proxy/account/api/v1/accounts`
  and the UI service forwards it to `http://localhost:8081/api/v1/accounts`. Status, body and the headers
  `Idempotent-Replayed`, `X-Request-ID`, `Location` come back unchanged.
- **Only the five services.** The forwarder accepts only the configured service names and plain paths (no `..`),
  and passes on only `Content-Type`, `Accept`, `Idempotency-Key` and `X-Request-ID`.
- **Why a burst endpoint.** A browser sends only about 6 requests at a time to one server, which would hide the
  race in Challenge 1 and the double click in Challenge 2. `/api/ui/burst` starts one virtual thread per copy
  and releases them all together with a latch, like the `xargs -P` race scripts. Each copy gets its own
  `X-Request-ID` (`ui-xxxx-1`, `-2`, …).
- **A service that is down** answers 502 `SERVICE_UNREACHABLE` (504 `SERVICE_TIMEOUT` if it does not answer in
  time), in the team's error format, so the page shows it like any other error.

## Code layout

```
src/main/java/com/moneytransfer/ui/
  proxy/ProxyForwarder      the forwarder and the burst (plain Java, JDK HttpClient)
  web/ProxyController       /proxy/{service}/** and /api/ui/burst
  web/UiController          /api/ui/services and /health
  config/                   UiProperties (service URLs, timeouts) and the forwarder bean
src/main/resources/static/  index.html, styles.css, app.js (the whole page)
```

## Limitations

- It is a demo tool, not a product: no login, and the admin switches are open. Do not expose it beyond your
  machine.
- The checks on each tab assume the default settings (for example the ₹1,00,000 cap and 10 transfers per
  minute). With other settings a ✗ may simply mean a different limit.
- Results are not saved; reload the page and the request log starts empty.
