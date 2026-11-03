#!/usr/bin/env bash
# Evidence that repeated journal posts never create a second journal: N identical posts for the
# same transfer at the same moment. Expect exactly one 201, the rest 200, one journalId, one journal.
set -euo pipefail
BASE="${LEDGER_URL:-http://localhost:8083}"
N="${N:-10}"
T="parallel-$(date +%s)"
OUT="${OUT_DIR:-evidence/ledger-idempotency/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
BODY="{\"transferId\":\"$T\",\"currency\":\"INR\",\"entries\":[{\"accountId\":\"ACC-1001\",\"direction\":\"DEBIT\",\"amount\":75.00},{\"accountId\":\"ACC-2002\",\"direction\":\"CREDIT\",\"amount\":75.00}]}"
post() { curl -s -o "$OUT/post-$1.json" -w "$1 %{http_code}\n" -H "Content-Type: application/json" -d "$BODY" "$BASE/api/v1/journals"; }
export -f post; export OUT BODY BASE
{
  echo "== $N identical journal posts for transfer $T   $(date -Is)"
  seq -w 1 "$N" | xargs -P "$N" -I{} bash -c 'post {}' | sort > "$OUT/status-codes.txt"
  echo "201 (created):         $(grep -c ' 201$' "$OUT/status-codes.txt" || true)"
  echo "200 (already existed): $(grep -c ' 200$' "$OUT/status-codes.txt" || true)"
  echo "distinct journalIds:   $(grep -ho '"journalId":"[^"]*"' "$OUT"/post-*.json | sort -u | wc -l | tr -d ' ')"
  echo
  echo "== Journals stored for the transfer (expect exactly one)"
  curl -s "$BASE/api/v1/journals/$T" | tee "$OUT/journals.json"; echo
} | tee "$OUT/summary.txt"
