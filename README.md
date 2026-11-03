# Money Transfer – Containerised Deployment

Five Spring Boot microservices plus a demo dashboard, each with its own PostgreSQL database, deployed
with Docker Compose (Assignment 2, Part A) and on Minikube (Part B).

```bash
cp .env.example .env          # then replace every "change-me" in .env
docker compose up --build     # builds 6 images, starts 11 containers
```

Open **http://localhost:8080** when every container shows `(healthy)`.

## Repository layout

```
money-transfer/
├── docker-compose.yml        the whole system (Task 2)
├── .env.example              placeholders only; copy to .env (never committed)
├── account-service/          Dockerfile, .dockerignore, source (same layout for each service)
├── transfer-service/
├── ledger-service/
├── limits-service/
├── notification-service/
├── ui-service/               demo dashboard
├── deploy-scripts/           verify, restart, diagnose, Kubernetes deploy/scale/self-heal
├── k8s/                      Kubernetes manifests (Part B)
├── evidence/                 Assignment 1 evidence, plus deployment/, troubleshooting/, k8s/
└── TROUBLESHOOTING.md        deployment problem playbook and write-up template (Task 3)
```

## Prerequisites

- **Docker Desktop** (Windows: with the WSL 2 back end), with at least 6 GB of memory allowed under
  *Settings → Resources*
- **Git Bash** (comes with Git for Windows) to run the scripts in `deploy-scripts/`
- No Java or Maven needed on the host: the images build the jars themselves

## 1. Containerisation (Task 1)

Each service has a two-stage **Dockerfile**:

| Stage | Base image | What it does |
| --- | --- | --- |
| build | `maven:3.9-eclipse-temurin-21` | Downloads dependencies (cached while `pom.xml` is unchanged), then `mvn package` |
| run | `eclipse-temurin:21-jre-alpine` | Only the jar and a Java runtime; runs as a non-root user; a `HEALTHCHECK` calls `/health` |

**Configuration comes only from environment variables.** The images contain no passwords: the services'
`application.yml` has no default password, `.env` files are excluded by each `.dockerignore` and by
`.gitignore`, and Compose passes the values in at start-up. Check it yourself:

```bash
docker image inspect --format '{{json .Config.Env}}' money-transfer/account-service:1.0.0
#   ["PATH=…","JAVA_HOME=…","PORT=8081","JAVA_OPTS=…"]     <- no password
docker run --rm --entrypoint ls money-transfer/account-service:1.0.0 -a /app
#   app.jar                                                 <- no .env file
```

Build without starting: `docker compose build`. List the images: `docker images "money-transfer/*"`.

## 2. Deployment with Docker Compose (Task 2)

```bash
docker compose up --build -d          # build and start in the background
docker compose ps                     # every container should become (healthy), about 1 minute
deploy-scripts/verify-deployment.sh   # health of every service + a transfer through all five services
```

| Container | Port on your machine | Purpose |
| --- | --- | --- |
| ui-service | 8080 | Demo dashboard |
| account-service, transfer-service, ledger-service, limits-service, notification-service | 8081, 8082, 8083, 8084, 8085 | The APIs (Swagger at `/swagger-ui.html`) |
| transfer-db, account-db, ledger-db, limits-db, notification-db | 5433 to 5437 | PostgreSQL, one per service (for DBeaver) |

**Start-up order** is enforced by health checks: each service waits for its database to pass
`pg_isready`; Transfer waits for the four services it calls to be healthy; the UI waits for Transfer.

**Inside the network** the services find each other by name: Transfer calls `http://account-service:8081`,
and Account connects to `jdbc:postgresql://account-db:5432/account_db`. `localhost` would mean the
container itself.

**Useful commands**

```bash
docker compose logs -f transfer-service                 # follow one service's logs
docker compose logs | grep <X-Request-ID>               # one request across every service
docker inspect --format '{{json .State.Health}}' money-transfer-account-service-1
docker compose exec transfer-service curl -s http://account-service:8081/health
```

**Stop and restart**

```bash
deploy-scripts/restart-check.sh       # stop, start again, show the data survived
docker compose down                   # remove containers (data stays in the volumes)
docker compose down -v                # remove containers AND data: a clean start
```

## 3. Deployment troubleshooting (Task 3)

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md): the problems this setup is most likely to hit, how to diagnose
each with `docker compose ps`, `logs` and `inspect`, and a template for the report.
`deploy-scripts/diagnose.sh <service>` saves the diagnosis to `evidence/troubleshooting/`.

## 4. Kubernetes on Minikube (Part B)

See [k8s/README.md](k8s/README.md). In short:

```bash
minikube start --cpus 4 --memory 6g
cp k8s/secrets.example.yaml k8s/secrets.yaml      # set the five passwords
deploy-scripts/k8s-deploy.sh                       # build images inside Minikube, apply everything
kubectl -n money-transfer port-forward svc/ui-service 8080:8080        # terminal 2
kubectl -n money-transfer port-forward svc/transfer-service 8082:8082  # terminal 3
deploy-scripts/verify-deployment.sh k8s           # the main workflow through Kubernetes
deploy-scripts/k8s-scale-demo.sh                  # 3 Account pods, the race still allows exactly 5
deploy-scripts/k8s-self-heal-demo.sh              # delete a pod, it is replaced, transfers keep working
```

## Configuration reference

| Variable in `.env` | Required | Used by |
| --- | --- | --- |
| `TRANSFER_DB_PASSWORD`, `ACCOUNT_DB_PASSWORD`, `LEDGER_DB_PASSWORD`, `LIMITS_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` | yes | each database and its service |
| `APP_VERSION` | no (1.0.0) | image tags |
| `HOLD_STRATEGY` | no (atomic) | Account; `naive` only for the Challenge 1 "before" demo |
| `ADMIN_ENDPOINTS_ENABLED` | no (true) | Ledger and Notification fault switches |
| `VELOCITY_MAX_TRANSFERS` | no (10) | Limits fraud rule |
| `*_DB_HOST_PORT` | no (5433 to 5437) | host ports for the databases; change one if it is in use |

If a required password is missing, `docker compose up` stops at once and names it.

**Changing a password later:** PostgreSQL applies `POSTGRES_PASSWORD` only when its volume is first created.
After changing a password in `.env`, reset that database with `docker compose down` and
`docker volume rm money-transfer_<name>-db-data` (this deletes its data).
