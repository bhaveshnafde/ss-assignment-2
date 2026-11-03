#!/usr/bin/env bash
# Advanced task: scaling. Scales Account to 3 pods, then fires 25 holds of 100 at once against an
# account holding 500. The requests are spread over the 3 pods, and still exactly 5 succeed:
# correctness comes from the database (one conditional UPDATE), not from one process.
#
# Needs the UI port-forward:  kubectl -n money-transfer port-forward svc/ui-service 8080:8080
set -uo pipefail
NS=money-transfer
UI="${UI_URL:-http://localhost:8080}"
REPLICAS="${REPLICAS:-3}"
OUT="${OUT_DIR:-evidence/k8s/scaling-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
RUN="k8s$(date +%s)"
{
  echo "== Before: Account pods"; kubectl -n $NS get pods -l app=account-service -o wide
  echo; echo "== kubectl scale deployment/account-service --replicas=$REPLICAS"
  kubectl -n $NS scale deployment/account-service --replicas="$REPLICAS"
  kubectl -n $NS rollout status deployment/account-service --timeout=300s
  echo; echo "== After: Account pods"; kubectl -n $NS get pods -l app=account-service -o wide
  echo; echo "== Hold race across $REPLICAS pods: 25 holds of 100 against 500, released at the same instant"
  ACC="UI-$RUN"
  curl -s -o /dev/null -w "open account $ACC: HTTP %{http_code}\n" -H "Content-Type: application/json" \
    -d "{\"accountId\":\"$ACC\",\"holderName\":\"Scale demo\",\"currency\":\"INR\",\"initialBalance\":500}" \
    "$UI/proxy/account/api/v1/accounts"
  bodies=$(for i in $(seq 1 25); do printf '{"transferId":"%s-h%02d","sourceAccountId":"%s","destinationAccountId":"ACC-1001","amount":100,"currency":"INR"}' "$RUN" "$i" "$ACC"; [[ $i -lt 25 ]] && printf ','; done)
  curl -s -H "Content-Type: application/json" -H "X-Request-ID: $RUN" \
    -d "{\"service\":\"account\",\"method\":\"POST\",\"path\":\"/api/v1/holds\",\"bodies\":[$bodies]}" \
    "$UI/api/ui/burst" > "$OUT/burst-answers.json"
  echo "succeeded (201): $(grep -o '"status":201' "$OUT/burst-answers.json" | wc -l | tr -d ' ')"
  echo "refused   (409): $(grep -o '"status":409' "$OUT/burst-answers.json" | wc -l | tr -d ' ')"
  echo; echo "== Account afterwards (available must be 0, never negative)"
  curl -s "$UI/proxy/account/api/v1/accounts/$ACC"; echo
  echo; echo "== Which pod handled which request (from each pod's own log)"
  for pod in $(kubectl -n $NS get pods -l app=account-service -o name); do
    placed=$(kubectl -n $NS logs "$pod" | grep -c "Hold placed for transfer $RUN" || true)
    refused=$(kubectl -n $NS logs "$pod" | grep -c "Hold for transfer $RUN.* refused" || true)
    echo "  ${pod#pod/}: placed $placed, refused $refused"
  done
} 2>&1 | tee "$OUT/scaling.txt"
echo; echo "Scale back with: kubectl -n $NS scale deployment/account-service --replicas=2"
