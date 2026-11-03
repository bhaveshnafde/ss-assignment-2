# Evidence

Proof, from the running system, of every claim the report makes about the engineering challenges.
Each sub-folder covers one challenge. Its README lists each claim and links the file that proves it.

| Folder | Challenge | Main claim |
| --- | --- | --- |
| [challenge-1-concurrency](challenge-1-concurrency/README.md) | 1 · Contended resource | 25 parallel holds of ₹100 on ₹500: exactly 5 succeed; the naive version lets all 25 through |
| [challenge-2-idempotency](challenge-2-idempotency/README.md) | 2 · Unsafe operation made idempotent | 10 identical transfers at once create one transfer; a repeat is replayed; a reused key with a different body is refused |
| [challenge-3c-saga](challenge-3c-saga/README.md) | 3C · Distributed transaction | The Ledger fails at step 3; steps 2 and 1 are undone in reverse order; nothing stays reserved |
| [dependency-failure](dependency-failure/README.md) | Slow or failing dependency (our own mock) | The SMS provider fails twice; the transfer stays COMPLETED; the third attempt sends one SMS |
| [tests](tests/README.md) | Automated tests | `mvn verify` output per service; tests are named after the business rules |

[run-info.txt](run-info.txt) records when the evidence was collected, the git commit and each service's health.

## How the evidence is produced

All files are written by scripts against the real services, not edited by hand.

1. Start the five services and their databases (each service's README).
2. From the repository root, in a bash shell (Git Bash on Windows):

   ```bash
   evidence/collect-all.sh                  # every challenge
   RUN_TESTS=1 evidence/collect-all.sh      # the same, plus mvn verify in each service
   ```

3. For the Challenge 1 "before" picture, restart the Account service with `HOLD_STRATEGY=naive`, run
   `evidence/collect-all.sh naive`, then restart Account normally.
4. Add the dashboard screenshots listed in each README to its `screenshots/` folder
   (dashboard: http://localhost:8080).

Re-running overwrites the previous results, so every link here always points at the latest run.
Every request carries an `X-Request-ID`, written into the summaries; search for it in a service's JSON log
to see the same call from the inside.
