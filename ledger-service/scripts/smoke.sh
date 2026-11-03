#!/usr/bin/env bash
# Quick check of every Ledger path.
set -uo pipefail

BASE="${LEDGER_URL:-http://localhost:8083}"
T="smoke-$(date +%s)"

req() { # $1 label, $2 method, $3 path, $4 body (optional)
  echo "-- $1"
  if [[ -n "${4:-}" ]]; then
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" -H "Content-Type: application/json" -d "$4" "$BASE$3"
  else
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" "$BASE$3"
  fi
}
journal() { # $1 transferId, $2 debit amount, $3 credit amount
  echo "{\"transferId\":\"$1\",\"currency\":\"INR\",\"entries\":[{\"accountId\":\"ACC-1001\",\"direction\":\"DEBIT\",\"amount\":$2},{\"accountId\":\"ACC-2002\",\"direction\":\"CREDIT\",\"amount\":$3}]}"
}

req "health" GET /health
req "post journal (expect 201)" POST /api/v1/journals "$(journal $T 500.00 500.00)"
req "same journal again (expect 200, same journalId)" POST /api/v1/journals "$(journal $T 500 500)"
req "same transfer, different amount (expect 422 JOURNAL_MISMATCH)" POST /api/v1/journals "$(journal $T 900 900)"
req "BR5 unbalanced (expect 422 UNBALANCED_JOURNAL)" POST /api/v1/journals "$(journal $T-u 500 400)"
req "reverse it (expect 201, directions swapped)" POST "/api/v1/journals/$T/reversal"
req "reverse again (expect 200)" POST "/api/v1/journals/$T/reversal"
req "both journals for the transfer" GET "/api/v1/journals/$T"
req "reverse unknown transfer (expect 404 JOURNAL_NOT_FOUND)" POST "/api/v1/journals/$T-none/reversal"
req "ACC-1001 history, newest first" GET "/api/v1/journals?accountId=ACC-1001&limit=5"
