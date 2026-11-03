#!/usr/bin/env bash
# Quick check of the main paths against the seeded demo accounts.
set -uo pipefail

BASE="${ACCOUNT_URL:-http://localhost:8081}"
T="smoke-$(date +%s)"

req() { # $1 label, $2 method, $3 path, $4 body (optional)
  echo "-- $1"
  if [[ -n "${4:-}" ]]; then
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" -H "Content-Type: application/json" -d "$4" "$BASE$3"
  else
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" "$BASE$3"
  fi
}

req "health" GET /health
req "list accounts" GET /api/v1/accounts
req "hold 100 from ACC-1001 (expect 201)" POST /api/v1/holds \
  "{\"transferId\":\"$T-a\",\"sourceAccountId\":\"ACC-1001\",\"destinationAccountId\":\"ACC-2002\",\"amount\":100.00,\"currency\":\"INR\"}"
req "same hold again (expect 200, nothing changes)" POST /api/v1/holds \
  "{\"transferId\":\"$T-a\",\"sourceAccountId\":\"ACC-1001\",\"destinationAccountId\":\"ACC-2002\",\"amount\":100.00,\"currency\":\"INR\"}"
req "capture it (expect 200; 100 moves to ACC-2002)" POST "/api/v1/holds/$T-a/capture"
req "release after capture (expect 409 HOLD_ALREADY_CAPTURED)" POST "/api/v1/holds/$T-a/release"
req "hold then release (expect 201, then 200)" POST /api/v1/holds \
  "{\"transferId\":\"$T-b\",\"sourceAccountId\":\"ACC-1001\",\"destinationAccountId\":\"ACC-2002\",\"amount\":50.00,\"currency\":\"INR\"}"
req "  release" POST "/api/v1/holds/$T-b/release"
req "BR2 empty account (expect 409 INSUFFICIENT_FUNDS)" POST /api/v1/holds \
  "{\"transferId\":\"$T-c\",\"sourceAccountId\":\"ACC-EMPTY\",\"destinationAccountId\":\"ACC-2002\",\"amount\":1,\"currency\":\"INR\"}"
req "BR4 frozen account (expect 422 ACCOUNT_NOT_ACTIVE)" POST /api/v1/holds \
  "{\"transferId\":\"$T-d\",\"sourceAccountId\":\"ACC-FROZEN\",\"destinationAccountId\":\"ACC-2002\",\"amount\":1,\"currency\":\"INR\"}"
req "BR4 currency mismatch (expect 422 CURRENCY_MISMATCH)" POST /api/v1/holds \
  "{\"transferId\":\"$T-e\",\"sourceAccountId\":\"ACC-USD-1\",\"destinationAccountId\":\"ACC-2002\",\"amount\":1,\"currency\":\"INR\"}"
req "release unknown hold (expect 404 HOLD_NOT_FOUND)" POST "/api/v1/holds/$T-zzz/release"
req "ACC-1001 afterwards" GET /api/v1/accounts/ACC-1001
