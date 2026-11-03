#!/usr/bin/env bash
# Challenge 3C evidence: force a failure at step 3 (Ledger) and show steps 2 and 1 being undone.
#
# With the dev stubs:      USE_STUBS=1 scripts/challenge3c-saga-failure.sh
# With the real services:  scripts/challenge3c-saga-failure.sh
#
# Either way the script switches the Ledger to fail journal POSTS (503) for the run and back after.
# Reversals keep working, so the saga can undo everything and end FAILED.
# (Stopping the Ledger completely is a different demo: then even the undo of step 3 cannot run, so the
#  transfer stays COMPENSATING with the money held, and the recovery job finishes the undo once the
#  Ledger is back.)
set -euo pipefail

BASE="${TRANSFER_URL:-http://localhost:8082}"
ACCOUNT="${ACCOUNT_URL:-http://localhost:8081}"
LEDGER="${LEDGER_URL:-http://localhost:8083}"
OUT="${OUT_DIR:-evidence/challenge-3c-saga/run-$(date +%Y%m%d-%H%M%S)}"
KEY="saga-demo-$(date +%s)"
mkdir -p "$OUT"

account_state() {
  if [[ "${USE_STUBS:-0}" == "1" ]]; then
    curl -s -X POST "$ACCOUNT/stub/state"
  else
    curl -s "$ACCOUNT/api/v1/accounts/ACC-1001"
  fi
}

ledger_fail() { # $1 = true | false
  if [[ "${USE_STUBS:-0}" == "1" ]]; then
    local on=$([[ "$1" == "true" ]] && echo on=true || echo on=false)
    curl -s -X POST "$LEDGER/stub/fail?$on" >/dev/null
  else
    curl -s -X PUT -H "Content-Type: application/json" -d "{\"failPosts\":$1,\"postDelayMs\":0}" \
      "$LEDGER/api/v1/admin/faults" >/dev/null
  fi
}
trap 'ledger_fail false' EXIT

{
  echo "== Challenge 3C: saga with compensation   $(date -Is)"
  echo "Account ACC-1001 BEFORE:"; account_state | tee "$OUT/account-before.json"; echo
  ledger_fail true
  echo "Ledger switched to fail journal posts (503)"
  echo
  echo "== POST /transfers (step 3 will fail)"
  curl -s -D "$OUT/response.headers.txt" -o "$OUT/response.body.json" \
       -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -H "X-Request-ID: $KEY" \
       -d '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":250.00,"currency":"INR"}' \
       "$BASE/api/v1/transfers"
  head -1 "$OUT/response.headers.txt"
  cat "$OUT/response.body.json"; echo
  TID=$(grep -o '"transferId":"[^"]*"' "$OUT/response.body.json" | head -1 | cut -d'"' -f4)
  echo
  echo "== Saga steps for transfer $TID (each step, then the undo in reverse order)"
  curl -s "$BASE/api/v1/transfers/$TID/saga-steps" | tee "$OUT/saga-steps.json"; echo
  echo
  echo "== Final transfer state (expect FAILED)"
  curl -s "$BASE/api/v1/transfers/$TID" | tee "$OUT/transfer.json"; echo
  ledger_fail false
  echo
  echo "== Account ACC-1001 AFTER (balance and available back to BEFORE; nothing on hold for this transfer)"
  account_state | tee "$OUT/account-after.json"; echo
  if [[ "${USE_STUBS:-0}" != "1" ]]; then
    echo
    echo "== Hold for this transfer (expect RELEASED)"
    curl -s "$ACCOUNT/api/v1/holds/$TID" | tee "$OUT/hold.json"; echo
    echo
    echo "== Ledger for this transfer (expect 404 JOURNAL_NOT_FOUND: nothing was recorded)"
    curl -s "$LEDGER/api/v1/journals/$TID" | tee "$OUT/ledger.json"; echo
  fi
  echo
  echo "Logs for this run: grep for requestId $KEY in each service's output."
} | tee "$OUT/summary.txt"
