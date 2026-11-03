# Challenge 2 · Sending money is safe to repeat

**Unsafe operation:** `POST /api/v1/transfers` (it moves money).
**Mechanism:** a required `Idempotency-Key`, claimed by a primary-key insert into `idempotency_keys`
together with a SHA-256 fingerprint of the request; the stored answer is replayed with
`Idempotent-Replayed: true`. Inside the system every call is keyed by `transferId` with a UNIQUE constraint.
Code: [TransferClaimer](../../transfer-service/src/main/java/com/moneytransfer/transfer/application/TransferClaimer.java),
[RequestFingerprint](../../transfer-service/src/main/java/com/moneytransfer/transfer/application/RequestFingerprint.java).

## Claims and the files that prove them

| # | Claim | Evidence | What to look for |
| --- | --- | --- | --- |
| C2.1 | 10 identical requests with one key, sent at the same moment, create **one** transfer | [transfer/summary.txt](transfer/summary.txt) | `distinct transferIds returned: 1`, `originals (replayed=false): 1` |
| C2.2 | The others are answered without a second effect (replayed, or 409 while the first is running) | [transfer/summary.txt](transfer/summary.txt), `transfer/parallel-*.headers.txt` | status codes 201 and 409 only; `Idempotent-Replayed: true` on repeats |
| C2.3 | A repeat after completion returns the identical answer | [transfer/repeat.headers.txt](transfer/repeat.headers.txt), [transfer/repeat.body.json](transfer/repeat.body.json) | `Replay is byte-identical to the original: YES` in the summary |
| C2.4 | The same key with a different amount is refused | [transfer/different-body.headers.txt](transfer/different-body.headers.txt), [transfer/different-body.body.json](transfer/different-body.body.json) | `422`, `IDEMPOTENCY_KEY_REUSED` |
| C2.5 | A repeated internal call never duplicates: 10 identical journal posts make one journal | [ledger-parallel-post/summary.txt](ledger-parallel-post/summary.txt), [ledger-parallel-post/journals.json](ledger-parallel-post/journals.json) | one 201, the rest 200; one journal |
| C2.6 | 10 identical notifications at once send one SMS | [notification-parallel/summary.txt](notification-parallel/summary.txt), [notification-parallel/stored.json](notification-parallel/stored.json) | one notification, `attempts: 1`, one history entry |

Optional database check (C2.1): the summary prints the `SELECT … FROM transfers WHERE idempotency_key = …`
query; run it with `psql` and save the result as `transfer/db-check.txt`. It returns one row.

## Also proved by automated tests

- [TransferApiIntegrationTest](../../transfer-service/src/test/java/com/moneytransfer/transfer/web/TransferApiIntegrationTest.java) · `tenIdenticalRequestsAtTheSameMomentCreateExactlyOneTransfer`, `completedTransferReturns201_andARepeatReplaysTheSameResponseWithNoSecondEffect`, `sameKeyWithADifferentBodyIsRejectedWith422`

## Screenshots to add

| File | What it shows |
| --- | --- |
| `screenshots/dashboard-challenge2.png` | Dashboard Challenge 2 tab with scenarios A to D all ✓ |

## Reproduce

`evidence/collect-all.sh`
