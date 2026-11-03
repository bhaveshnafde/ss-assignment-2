# Deployment troubleshooting (Task 3)

The brief asks for **one genuine deployment problem** your team hit, with its symptom, diagnosis,
fix, validation and what you learnt. Keep a note of the first real problem you meet while
running `docker compose up --build`. The playbook below covers the problems this setup is most
likely to cause, so you can recognise and diagnose yours quickly.

## Capture the evidence while the problem is happening

```bash
docker compose ps -a                            # which container is Exited, Restarting or unhealthy
docker compose logs --tail 100 <service>        # the error message
docker inspect --format '{{json .State.Health}}' money-transfer-<service>-1   # health check history
deploy-scripts/diagnose.sh <service>            # all of the above, saved to evidence/troubleshooting/
```

Save the output **before** fixing anything. After the fix, run `deploy-scripts/verify-deployment.sh`
and keep its output as the validation.

## Playbook

| # | Symptom | Diagnosis: what the tools show | Cause | Fix |
| --- | --- | --- | --- | --- |
| P1 | `docker compose up` stops with `Bind for 0.0.0.0:5433 failed: port is already allocated` | `docker ps` shows the old `transfer-db` container from `docker-compose.dev.yml` still running | The per-service dev databases from Assignment 1 hold the same host ports | `docker compose -f transfer-service/docker-compose.dev.yml down` for each dev database, or change `*_DB_HOST_PORT` in `.env` |
| P2 | A service keeps restarting, or stays `unhealthy` | Its logs: `FATAL: password authentication failed for user "account_user"` | The database volume was first created with a different password. Postgres applies `POSTGRES_PASSWORD` **only on first start**, so changing `.env` later has no effect on an existing volume | Reset that database: `docker compose down`, `docker volume rm money-transfer_account-db-data`, `docker compose up -d`. Or set `.env` back to the original password |
| P3 | A service starts but cannot reach its database: `Connection to localhost:5434 refused` | `docker inspect` shows `DB_URL` is missing or points at `localhost` | Inside a container, `localhost` is the container itself, not the database. The service fell back to its local-development default | Set `DB_URL=jdbc:postgresql://account-db:5432/account_db` (the Compose service name) |
| P4 | Transfers fail with 503 `ACCOUNT_UNAVAILABLE`, though Account is running | Transfer's logs: `UnknownHostException` or `Connection refused` to `localhost:8081` | `ACCOUNT_SERVICE_URL` missing or set to `localhost` in the Transfer container | Use `http://account-service:8081`. Test from inside: `docker compose exec transfer-service curl -s http://account-service:8081/health` |
| P5 | Right after `up`, the first transfers fail, then later ones work | Transfer's logs show connection errors at start-up; `docker compose ps` shows Account still `health: starting` at that moment | Transfer started before its dependencies were ready (`depends_on` without a health condition only waits for the container to start, not for the app) | `depends_on: { account-service: { condition: service_healthy } }` plus a `HEALTHCHECK` in each Dockerfile |
| P6 | Container `Exited (1)` immediately | Logs: `Could not resolve placeholder 'DB_PASSWORD'` | The password variable was not passed to the container; the image deliberately has no default | Set it in `.env`; `docker compose config` shows what each container will receive |
| P7 | `docker compose up` stops before starting anything: `required variable ACCOUNT_DB_PASSWORD is missing a value` | — | No `.env` file next to `docker-compose.yml` | `cp .env.example .env` and set the passwords |
| P8 | Build fails at `mvn dependency:go-offline` or `apk add` | Build log | No internet access or a proxy during the image build | Check the network or proxy and build again; Maven needs to download dependencies the first time |
| P9 | Times in the logs are 5 h 30 min behind | — | Containers run in UTC on purpose | Not a bug; mention in the report |

P1 and P2 are the most likely to happen to you, because Assignment 1's local databases are still on your
machine. Either one is a genuine, well-documented problem for the report.

## Write-up template (report section 3, 3 marks)

**Problem (0.5).** What we ran, what we expected, what happened instead. Paste the exact error line.

**Diagnosis (1.0).** The commands we used and what each showed, in order:
1. `docker compose ps -a` → which container, and its state (screenshot or `01-ps.txt`)
2. `docker compose logs <service>` → the error message (`02-logs-<service>.txt`)
3. `docker inspect …` → health history, restart count, the environment it actually received (`03-inspect-<service>.txt`)
4. Why that explains the symptom (the root cause, in one or two sentences)

**Solution and validation (1.0).** The change, as a diff or the corrected lines (for example, from
`docker-compose.yml`). Then the proof: `docker compose ps` all healthy, and `verify-deployment.sh`
showing the transfer completing across all five services.

**Learning (0.5).** What it taught us about containers: for example, `localhost` means something different
inside a container; state in a volume outlives configuration changes; "started" is not "ready". Then how we
would prevent it in a real deployment: health-based start-up order, configuration checked at start
(`${VAR:?}`), secrets managed outside the image, a smoke test after every deploy.
