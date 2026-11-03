#!/usr/bin/env bash
# N identical notify requests for one transfer at the same moment (what Transfer's retries can cause).
# The provider is made slow so they really overlap. Expect: all 202, ONE notification, ONE provider attempt.
#   scripts/parallel-notify.sh            (N=10)
set -euo pipefail
BASE="${NOTIFICATION_URL:-http://localhost:8085}"
N="${N:-10}"
T="race-$(date +%s)"
OUT="${OUT_DIR:-evidence/notification-parallel/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
send() { curl -s -o "$OUT/r-$1.json" -w "$1 %{http_code}\n" -H "Content-Type: application/json" \
  -d "{\"transferId\":\"$T\",\"accountId\":\"ACC-1001\",\"channel\":\"SMS\",\"template\":\"TRANSFER_COMPLETED\",\"message\":\"You sent 5.00 INR\"}" \
  "$BASE/api/v1/notifications"; }
export -f send; export OUT T BASE
{
  echo "== Notification: $N identical requests at once   $(date -Is)"
  curl -s -o /dev/null -X PUT -H "Content-Type: application/json" -d '{"delayMs":300,"failRate":0,"failNext":0}' "$BASE/api/v1/admin/provider"
  seq -w 1 "$N" | xargs -P "$N" -I{} bash -c 'send {}' | sort > "$OUT/status-codes.txt"
  curl -s -o /dev/null -X PUT -H "Content-Type: application/json" -d '{"delayMs":0,"failRate":0,"failNext":0}' "$BASE/api/v1/admin/provider"
  echo "202 responses: $(grep -c ' 202$' "$OUT/status-codes.txt" || true) of $N"
  echo
  echo "== Stored for transfer $T (expect 1 notification, attempts 1, history of 1)"
  curl -s "$BASE/api/v1/notifications?transferId=$T" | tee "$OUT/stored.json"; echo
} | tee "$OUT/summary.txt"
