#!/usr/bin/env bash
# Advanced task: self-healing. Deletes one Account pod while transfers keep running; Kubernetes
# replaces the pod, and the remaining pod keeps serving, so the transfers still complete.
#
# Needs the Transfer port-forward:  kubectl -n money-transfer port-forward svc/transfer-service 8082:8082
set -uo pipefail
NS=money-transfer
TRANSFER="${TRANSFER_URL:-http://localhost:8082}"
OUT="${OUT_DIR:-evidence/k8s/self-healing-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
send() {
  local key="heal-$(date +%s%N)-$1"
  curl -s -o /dev/null -w "  transfer $1: HTTP %{http_code}\n" -H "Content-Type: application/json" -H "Idempotency-Key: $key" \
    -d '{"sourceAccountId":"ACC-2002","destinationAccountId":"ACC-1001","amount":1.00,"currency":"INR"}' "$TRANSFER/api/v1/transfers"
}
{
  echo "== Account pods before   $(date -Is)"; kubectl -n $NS get pods -l app=account-service -o wide
  VICTIM=$(kubectl -n $NS get pods -l app=account-service -o name | head -1)
  echo; echo "== Deleting $VICTIM"
  kubectl -n $NS delete "$VICTIM" --wait=false
  echo; echo "== Straight away: the old pod is terminating and a replacement is being created"
  sleep 2; kubectl -n $NS get pods -l app=account-service -o wide
  echo; echo "== Transfers while the pod is being replaced (the other pod serves them)"
  for i in 1 2 3; do send "$i"; sleep 2; done
  echo; echo "== Waiting for the Deployment to be back at full strength"
  kubectl -n $NS rollout status deployment/account-service --timeout=300s
  kubectl -n $NS get pods -l app=account-service -o wide
  echo; echo "== Kubernetes events: the ReplicaSet created the replacement"
  kubectl -n $NS get events --sort-by=.lastTimestamp | grep -i "account-service" | tail -8
  echo; echo "== A transfer after recovery"; send after
} 2>&1 | tee "$OUT/self-healing.txt"
