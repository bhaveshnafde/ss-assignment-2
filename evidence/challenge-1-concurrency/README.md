# Challenge 1 · A contended resource is never over-allocated

**Resource:** an account's available balance (Account service), and the same pattern on each account's
daily limit (Limits service).
**Mechanism:** one conditional SQL UPDATE, `available = available - :amount WHERE id = :account AND
available >= :amount`, plus a CHECK constraint `available >= 0`. Code:
[AccountJpaRepository](../../account-service/src/main/java/com/moneytransfer/account/persistence/AccountJpaRepository.java).

## Claims and the files that prove them

| # | Claim | Evidence | What to look for |
| --- | --- | --- | --- |
| C1.1 | 25 holds of ₹100 fired at the same moment against ₹500 succeed **exactly 5 times** | [atomic/summary.txt](atomic/summary.txt), [atomic/status-codes.txt](atomic/status-codes.txt) | `succeeded (201): 5`, `refused (409): 20` |
| C1.2 | The balance is never over-allocated: available ends at ₹0, never below | [atomic/account-after.json](atomic/account-after.json) | `"available":0.00`, `"onHold":500.00`, `"balance":500.00` |
| C1.3 | Exactly 5 holds exist in the database, totalling ₹500 | [atomic/holds.json](atomic/holds.json) | 5 entries, each `HELD` |
| C1.4 | The naive read-then-write version **does** over-allocate (proves the test detects the bug) | [naive/summary.txt](naive/summary.txt), [naive/account-after.json](naive/account-after.json) | far more than 5 succeed |
| C1.5 | The same race on a ₹500 daily limit also allows exactly 5 | [daily-limit/summary.txt](daily-limit/summary.txt), [daily-limit/limits-after.json](daily-limit/limits-after.json) | `succeeded (201): 5`, `usedToday` 500 |
| C1.6 | The fraud rule F1 refuses the 11th transfer within a minute | [velocity/summary.txt](velocity/summary.txt) | requests 11 and 12 refused with `VELOCITY_LIMIT` |

## Also proved by automated tests

- [AccountApiIntegrationTest](../../account-service/src/test/java/com/moneytransfer/account/web/AccountApiIntegrationTest.java) · `challenge1_25ParallelHoldsOf100Against500_exactly5Succeed`
- [LimitsApiIntegrationTest](../../limits-service/src/test/java/com/moneytransfer/limits/web/LimitsApiIntegrationTest.java) · `BR3_25ParallelReservationsOf100AgainstALimitOf500_exactly5Succeed`

## Screenshots to add

| File | What it shows |
| --- | --- |
| `screenshots/dashboard-challenge1.png` | Dashboard Challenge 1 tab: 5 green and 20 red squares, all checks ✓ |
| `screenshots/dashboard-challenge1-naive.png` | The same tab with Account started as `HOLD_STRATEGY=naive` |

## Reproduce

`evidence/collect-all.sh` (C1.1 to C1.3, C1.5, C1.6) and `evidence/collect-all.sh naive` (C1.4).
