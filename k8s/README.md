# Kubernetes on Minikube (Part B)

Targets: **Minikube deployment (2 marks)** and **scaling / self-healing (1 mark)**. The brief says Running
pods are not enough: the main workflow must work through Kubernetes, so every step below ends with a
real transfer.

## What is deployed

| File | Contents |
| --- | --- |
| `00-namespace.yaml` | Namespace `money-transfer` |
| `secrets.example.yaml` | Secret `db-credentials` with placeholder passwords; copy to `secrets.yaml` (git-ignored) |
| `10-databases.yaml` | 5 × PostgreSQL: a StatefulSet with a 1 Gi volume and a ClusterIP Service each (`transfer-db`, …) |
| `20-services.yaml` | 6 × Deployment + Service. Probes on `/health`: startup (Java start), readiness (take traffic), liveness (restart if stuck). An init container waits for the database |

| Deployment | Replicas | Why |
| --- | --- | --- |
| account-service | 2 | Stateless; the atomic SQL update keeps money correct across pods (the scaling demo) |
| limits-service | 1 | Stateless, could be scaled the same way |
| ledger-service, notification-service | 1 | Their demo fault switches are kept in memory per pod |
| transfer-service | 1 | Its recovery job assumes one instance |
| ui-service | 1 | Demo dashboard |

Services find each other by name exactly as in Compose (`http://account-service:8081`,
`jdbc:postgresql://account-db:5432/account_db`), so the same images run unchanged.
`ui-service` and `transfer-service` are also NodePorts (30080, 30082).

## Steps

**One-time install (Windows):** Docker Desktop, then minikube and kubectl:
`winget install Kubernetes.minikube` and `winget install Kubernetes.kubectl`.

```bash
# 1. Start a cluster with enough room for 11 pods
minikube start --cpus 4 --memory 6g

# 2. Passwords (never commit k8s/secrets.yaml)
cp k8s/secrets.example.yaml k8s/secrets.yaml      # edit: replace every change-me

# 3. Build the images inside Minikube and deploy everything (5 to 10 minutes the first time)
deploy-scripts/k8s-deploy.sh

# 4. Reach it from your machine: keep these running in two more terminals
kubectl -n money-transfer port-forward svc/ui-service 8080:8080
kubectl -n money-transfer port-forward svc/transfer-service 8082:8082

# 5. Main workflow through Kubernetes: health of every service + a transfer across all five
deploy-scripts/verify-deployment.sh k8s
```

Stop Docker Compose first (`docker compose down`), or ports 8080 and 8082 are already taken.
Instead of port-forward you can use `minikube service ui-service -n money-transfer`, which opens a tunnel and a browser.

## Scaling (1 mark)

```bash
deploy-scripts/k8s-scale-demo.sh
```

It scales `account-service` to 3 pods, then fires 25 holds of ₹100 at the same instant against an account
holding ₹500. Kubernetes spreads them over the 3 pods, and still **exactly 5 succeed**. The script counts,
from each pod's own log, how many holds that pod placed and refused, which shows the work really was
shared. Output: `evidence/k8s/scaling-*/scaling.txt`.

By hand: `kubectl -n money-transfer scale deployment/account-service --replicas=3`, then
`kubectl -n money-transfer get pods -l app=account-service -o wide`.

## Self-healing

```bash
deploy-scripts/k8s-self-heal-demo.sh
```

It deletes one Account pod and sends transfers while Kubernetes replaces it. The other pod keeps
serving, so the transfers still return 201, and the Deployment returns to full strength with a new pod.
Output: `evidence/k8s/self-healing-*/self-healing.txt`.

By hand: `kubectl -n money-transfer delete pod <account pod>`, then
`kubectl -n money-transfer get pods -w` (a new pod appears within seconds).

## Useful commands

```bash
kubectl -n money-transfer get pods,svc -o wide
kubectl -n money-transfer describe pod <pod>          # events: probe failures, restarts, pulls
kubectl -n money-transfer logs deploy/transfer-service -f
kubectl -n money-transfer logs -l tier=app --prefix | grep <X-Request-ID>
kubectl -n money-transfer exec deploy/transfer-service -- curl -s http://account-service:8081/health
```

| Problem | Likely cause | Fix |
| --- | --- | --- |
| `ErrImagePull` / `ImagePullBackOff` | Image built on your machine's Docker, not Minikube's | Run `deploy-scripts/k8s-deploy.sh` (it builds inside Minikube), or `minikube image load money-transfer/<name>:1.0.0` |
| `CreateContainerConfigError` | Secret `db-credentials` missing | `kubectl apply -f k8s/secrets.yaml` |
| Pod stuck at `Init:0/1` | Its database is not ready yet | `kubectl -n money-transfer get pods -l tier=database`; check that database's logs |
| `OOMKilled` or pods `Pending` | Minikube too small | `minikube stop`, `minikube delete`, start again with `--memory 6g` |

## Clean up

`kubectl delete namespace money-transfer` (removes everything, including data), and `minikube stop`.
