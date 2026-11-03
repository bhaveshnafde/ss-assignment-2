#!/usr/bin/env bash
# Proves the deployed system works (Task 2): every container healthy, the services talk to each
# other, and the main workflow (a money transfer through all five services) succeeds.
#
#   deploy-scripts/verify-deployment.sh          against Docker Compose
#   deploy-scripts/verify-deployment.sh k8s      against Minikube, through
#       kubectl -n money-transfer port-forward svc/ui-service 8080:8080
#       kubectl -n money-transfer port-forward svc/transfer-service 8082:8082
#
# Saves everything it prints to evidence/deployment/<target>-<time>/ for the report.
set -uo pipefail

TARGET="${1:-compose}"
TRANSFER="${TRANSFER_URL:-http://localhost:8082}"
UI="${UI_URL:-http://localhost:8080}"
OUT="${OUT_DIR:-evidence/deployment/$TARGET-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
pass=0; fail=0
check() { # $1 description, $2 condition result (0 = ok)
  if [[ "$2" == 0 ]]; then echo "  PASS  $1"; pass=$((pass+1)); else echo "  FAIL  $1"; fail=$((fail+1)); fi
}

{
echo "== Deployment check ($TARGET)   $(date -Is)"
if [[ "$TARGET" == "k8s" ]]; then
  echo; echo "== kubectl get pods,svc"
  kubectl -n money-transfer get pods,svc -o wide
else
  echo; echo "== docker compose ps"
  docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
fi

echo; echo "== 1. Every service answers /health (through the UI service, which calls each one over the network)"
for s in transfer account ledger limits notification; do
  body=$(curl -s "$UI/proxy/$s/health")
  echo "  $s: $body"
  [[ "$body" == *'"status":"UP"'* ]]; check "$s is UP and its database is UP" $?
done

echo; echo "== 2. Main workflow: a transfer of 100.00 INR from ACC-1001 to ACC-2002"
before=$(curl -s "$UI/proxy/account/api/v1/accounts/ACC-1001")
echo "  ACC-1001 before: $before"
KEY="deploy-check-$(date +%s)"
curl -s -D "$OUT/transfer.headers.txt" -o "$OUT/transfer.json" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" -H "X-Request-ID: $KEY" \
  -d '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":100.00,"currency":"INR"}' \
  "$TRANSFER/api/v1/transfers"
status=$(head -1 "$OUT/transfer.headers.txt" | awk '{print $2}')
echo "  answer: HTTP $status $(cat "$OUT/transfer.json")"
[[ "$status" == 201 ]]; check "transfer accepted with 201" $?
grep -q '"status":"COMPLETED"' "$OUT/transfer.json"; check "transfer COMPLETED" $?
TID=$(grep -o '"transferId":"[^"]*"' "$OUT/transfer.json" | head -1 | cut -d'"' -f4)

echo; echo "== 3. Inter-service communication: the same transfer seen by every service"
sleep 3   # the SMS is sent in the background
curl -s "$TRANSFER/api/v1/transfers/$TID/saga-steps" > "$OUT/saga-steps.json"
echo "  saga steps: $(grep -o '"step":"[A-Z_]*","action":"[A-Z]*","outcome":"[A-Z]*"' "$OUT/saga-steps.json" | tr '\n' ' ')"
for step in LIMIT_RESERVATION FUNDS_HOLD LEDGER_JOURNAL CAPTURE; do
  grep -q "\"step\":\"$step\",\"action\":\"EXECUTE\",\"outcome\":\"SUCCEEDED\"" "$OUT/saga-steps.json"; check "Transfer called the next service: $step succeeded" $?
done
curl -s "$UI/proxy/limits/api/v1/limit-reservations/$TID" > "$OUT/limits.json"
grep -q '"status":"RESERVED"' "$OUT/limits.json"; check "Limits has the reservation" $?
curl -s "$UI/proxy/account/api/v1/holds/$TID" > "$OUT/hold.json"
grep -q '"status":"CAPTURED"' "$OUT/hold.json"; check "Account captured the hold" $?
curl -s "$UI/proxy/ledger/api/v1/journals/$TID" > "$OUT/ledger.json"
grep -q '"type":"TRANSFER"' "$OUT/ledger.json"; check "Ledger recorded the journal" $?
curl -s "$UI/proxy/notification/api/v1/notifications?transferId=$TID" > "$OUT/notification.json"
grep -q '"status":"SENT"' "$OUT/notification.json"; check "Notification sent the SMS" $?
after=$(curl -s "$UI/proxy/account/api/v1/accounts/ACC-1001")
echo "  ACC-1001 after:  $after"

echo; echo "== Result: $pass passed, $fail failed. transferId $TID, X-Request-ID $KEY"
if [[ "$TARGET" == "k8s" ]]; then
  echo "   Find this request in the logs:  kubectl -n money-transfer logs -l tier=app --prefix | grep $KEY"
else
  echo "   Find this request in the logs:  docker compose logs | grep $KEY"
fi
} 2>&1 | tee "$OUT/summary.txt"
grep -q "0 failed" "$OUT/summary.txt"
