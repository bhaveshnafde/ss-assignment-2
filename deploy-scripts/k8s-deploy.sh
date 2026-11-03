#!/usr/bin/env bash
# Builds the six images inside Minikube and deploys everything to the money-transfer namespace.
#
# Before:  minikube start --cpus 4 --memory 6g
#          cp k8s/secrets.example.yaml k8s/secrets.yaml   and set the five passwords
# Run from the repository root in Git Bash:  deploy-scripts/k8s-deploy.sh
set -euo pipefail
NS=money-transfer

echo "== 1. Point the docker CLI at Minikube's Docker, so the images are built where Kubernetes runs them"
eval "$(minikube -p minikube docker-env --shell bash)"

echo "== 2. Build the images"
for s in account ledger limits notification transfer ui; do
  echo "-- $s-service"
  docker build -t "money-transfer/$s-service:1.0.0" "./$s-service"
done

echo "== 3. Namespace and database passwords"
kubectl apply -f k8s/00-namespace.yaml
if [[ -f k8s/secrets.yaml ]]; then
  kubectl apply -f k8s/secrets.yaml
elif ! kubectl -n $NS get secret db-credentials >/dev/null 2>&1; then
  echo "No k8s/secrets.yaml and no db-credentials secret: copy k8s/secrets.example.yaml to k8s/secrets.yaml first."; exit 1
fi

echo "== 4. Databases"
kubectl apply -f k8s/10-databases.yaml
for d in transfer account ledger limits notification; do
  kubectl -n $NS rollout status statefulset/$d-db --timeout=180s
done

echo "== 5. Services"
kubectl apply -f k8s/20-services.yaml
for s in account ledger limits notification transfer ui; do
  kubectl -n $NS rollout status deployment/$s-service --timeout=300s
done

echo; kubectl -n $NS get pods,svc -o wide
cat <<MSG

Deployed. To use it from this machine, keep these two running in separate terminals:
  kubectl -n $NS port-forward svc/ui-service 8080:8080
  kubectl -n $NS port-forward svc/transfer-service 8082:8082
Then open http://localhost:8080 and run:  deploy-scripts/verify-deployment.sh k8s
(Stop Docker Compose first if it is running, or ports 8080 and 8082 will be in use.)
MSG
