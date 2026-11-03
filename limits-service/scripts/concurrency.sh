#!/usr/bin/env bash
# The daily limit is a contended resource, like an account balance: N parallel reservations of
# AMOUNT against a daily limit of LIMIT. Expect exactly LIMIT/AMOUNT to succeed and usage = LIMIT.
#   scripts/concurrency.sh                       (N=25, AMOUNT=100, LIMIT=500)
set -euo pipefail
BASE="${LIMITS_URL:-http://localhost:8084}"
N="${N:-25}"; AMOUNT="${AMOUNT:-100}"; LIMIT="${LIMIT:-500}"
A="ACC-RACE-$(date +%s)"
OUT="${OUT_DIR:-evidence/limits-concurrency/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
reserve() { curl -s -o "$OUT/r-$1.json" -w "$1 %{http_code}\n" -H "Content-Type: application/json" \
  -d "{\"transferId\":\"$A-t$1\",\"accountId\":\"$A\",\"amount\":$AMOUNT,\"currency\":\"INR\"}" "$BASE/api/v1/limit-reservations"; }
export -f reserve; export OUT A AMOUNT BASE
{
  echo "== Limits concurrency   $(date -Is)"
  curl -s -o /dev/null -X PUT -H "Content-Type: application/json" \
    -d "{\"dailyLimit\":$LIMIT,\"perTransferLimit\":$LIMIT}" "$BASE/api/v1/limits/$A"
  echo "Account $A given a daily limit of $LIMIT. Firing $N reservations of $AMOUNT at once; room for $(( LIMIT / AMOUNT ))."
  seq -w 1 "$N" | xargs -P "$N" -I{} bash -c 'reserve {}' | sort > "$OUT/status-codes.txt"
  echo "succeeded (201): $(grep -c ' 201$' "$OUT/status-codes.txt" || true)"
  echo "refused   (422): $(grep -c ' 422$' "$OUT/status-codes.txt" || true)"
  echo
  echo "== Limits afterwards (usedToday should equal the limit)"
  curl -s "$BASE/api/v1/limits/$A" | tee "$OUT/limits-after.json"; echo
} | tee "$OUT/summary.txt"
