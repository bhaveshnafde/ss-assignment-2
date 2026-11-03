# Challenge 3C · A multi-service transaction that is undone when step 3 fails

**Transaction:** one transfer across Limits (step 1), Account (steps 2 and 4) and Ledger (step 3),
orchestrated by Transfer with a persisted state machine.
**Forced failure:** the Ledger's fault switch (`PUT /api/v1/admin/faults {"failPosts": true}`) makes
journal posts answer 503, so step 3 fails after its retries.
Code: [TransferSagaOrchestrator](../../transfer-service/src/main/java/com/moneytransfer/transfer/domain/saga/TransferSagaOrchestrator.java).

## Claims and the files that prove them

| # | Claim | Evidence | What to look for |
| --- | --- | --- | --- |
| C3.1 | The client is told the transfer failed because a dependency was unavailable | [ledger-failure/response.headers.txt](ledger-failure/response.headers.txt), [ledger-failure/response.body.json](ledger-failure/response.body.json) | `503`, `LEDGER_UNAVAILABLE` |
| C3.2 | Steps 1 and 2 ran, step 3 failed after retries, then steps 2 and 1 were undone **in reverse order** | [ledger-failure/saga-steps.json](ledger-failure/saga-steps.json) | `LIMIT_RESERVATION` and `FUNDS_HOLD` SUCCEEDED, `LEDGER_JOURNAL` UNAVAILABLE, then COMPENSATE `FUNDS_HOLD`, then COMPENSATE `LIMIT_RESERVATION` |
| C3.3 | The transfer ends in an explicit final state | [ledger-failure/transfer.json](ledger-failure/transfer.json) | `"status":"FAILED"` |
| C3.4 | The money set aside was given back | [ledger-failure/hold.json](ledger-failure/hold.json), [ledger-failure/account-before.json](ledger-failure/account-before.json), [ledger-failure/account-after.json](ledger-failure/account-after.json) | hold `RELEASED`; balance and available identical before and after |
| C3.5 | The daily-limit reservation was released | [ledger-failure/limit-reservation.json](ledger-failure/limit-reservation.json) | `"status":"RELEASED"` |
| C3.6 | Nothing was recorded in the Ledger | [ledger-failure/ledger.json](ledger-failure/ledger.json) | 404 `JOURNAL_NOT_FOUND` |

The full run, in order, is in [ledger-failure/summary.txt](ledger-failure/summary.txt).

## Also proved by automated tests

- [TransferSagaOrchestratorTest](../../transfer-service/src/test/java/com/moneytransfer/transfer/domain/saga/TransferSagaOrchestratorTest.java) · `ledgerDownAtStep3_undoesSteps2And1InReverseOrderAndFails`, plus a failed undo, a crash mid-saga and an unknown capture outcome
- [TransferApiIntegrationTest](../../transfer-service/src/test/java/com/moneytransfer/transfer/web/TransferApiIntegrationTest.java) · `ledgerDown_returns503_andTheSagaStepsShowTheCompensation`

## Screenshots to add

| File | What it shows |
| --- | --- |
| `screenshots/dashboard-challenge3c.png` | Dashboard Challenge 3C tab: all checks ✓ and the saga timeline with the two undo steps |

## Reproduce

`evidence/collect-all.sh` (it switches the Ledger fault on for the run and always switches it off again).
