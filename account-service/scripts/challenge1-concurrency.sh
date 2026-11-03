#!/usr/bin/env bash
# Challenge 1 evidence: N parallel holds of AMOUNT against an account holding BALANCE.
# With the default (atomic) service: exactly BALANCE/AMOUNT succeed, the rest get 409
# INSUFFICIENT_FUNDS, and available ends at 0.
#
# Usage:  scripts/challenge1-concurrency.sh                      (N=25, AMOUNT=100, BALANCE=500)
#         N=50 AMOUNT=100 BALANCE=100 scripts/challenge1-concurrency.sh
#
# To show the bug the atomic update prevents, restart the service with HOLD_STRATEGY=naive and
# run this again with LABEL=naive: more holds succeed than there is money for.
set -euo pipefail

BASE="${ACCOUNT_URL:-http://localhost:8081}"
N="${N:-25}"; AMOUNT="${AMOUNT:-100}"; BALANCE="${BALANCE:-500}"; LABEL="${LABEL:-atomic}"
RUN="$(date +%Y%m%d-%H%M%S)"
OUT="${OUT_DIR:-evidence/challenge-1-concurrency/$LABEL-$RUN}"
SRC="RACE-$RUN"; DST="RACE-$RUN-D"
mkdir -p "$OUT"

open_account() { # $1 id, $2 balance
  curl -s -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" \
    -d "{\"accountId\":\"$1\",\"holderName\":\"Race test\",\"currency\":\"INR\",\"initialBalance\":$2}" \
    "$BASE/api/v1/accounts"
}

hold() { # $1 request number
  curl -s -o "$OUT/hold-$1.json" -w "$1 %{http_code}\n" -H "Content-Type: application/json" \
    -H "X-Request-ID: $SRC-$1" \
    -d "{\"transferId\":\"$SRC-t$1\",\"sourceAccountId\":\"$SRC\",\"destinationAccountId\":\"$DST\",\"amount\":$AMOUNT,\"currency\":\"INR\"}" \
    "$BASE/api/v1/holds"
}
export -f hold; export OUT SRC DST AMOUNT BASE

{
  echo "== Challenge 1: concurrency ($LABEL)   $(date -Is)"
  echo "Account $SRC opened with $BALANCE INR: HTTP $(open_account "$SRC" "$BALANCE")"
  echo "Account $DST opened with 0 INR: HTTP $(open_account "$DST" 0)"
  echo "Firing $N holds of $AMOUNT at the same moment; money exists for $(( BALANCE / AMOUNT ))."
  echo
  seq -w 1 "$N" | xargs -P "$N" -I{} bash -c 'hold {}' | sort > "$OUT/status-codes.txt"
  echo "succeeded (201): $(grep -c ' 201$' "$OUT/status-codes.txt" || true)"
  echo "refused   (409): $(grep -c ' 409$' "$OUT/status-codes.txt" || true)"
  echo "other:           $(grep -vc -E ' (201|409)$' "$OUT/status-codes.txt" || true)"
  echo
  echo "== Account afterwards (balance unchanged; available + onHold = balance)"
  curl -s "$BASE/api/v1/accounts/$SRC" | tee "$OUT/account-after.json"; echo
  echo
  echo "== Holds recorded: $(curl -s "$BASE/api/v1/accounts/$SRC/holds" | tee "$OUT/holds.json" | grep -o '"transferId"' | wc -l | tr -d ' ')"
  echo
  echo "Check in the database too:"
  echo "  SELECT id, balance, available FROM accounts WHERE id = '$SRC';"
  echo "  SELECT count(*), sum(amount) FROM holds WHERE source_account_id = '$SRC';"
} | tee "$OUT/summary.txt"
