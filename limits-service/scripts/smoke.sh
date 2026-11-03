#!/usr/bin/env bash
# Quick check of every Limits path.
set -uo pipefail

BASE="${LIMITS_URL:-http://localhost:8084}"
T="smoke-$(date +%s)"
A="ACC-SMOKE-$(date +%s)"

req() { # $1 label, $2 method, $3 path, $4 body (optional)
  echo "-- $1"
  if [[ -n "${4:-}" ]]; then
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" -H "Content-Type: application/json" -d "$4" "$BASE$3"
  else
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" "$BASE$3"
  fi
}
body() { echo "{\"transferId\":\"$1\",\"accountId\":\"$2\",\"amount\":$3,\"currency\":\"INR\"}"; }

req "health" GET /health
req "limits for a new account (defaults, nothing used)" GET "/api/v1/limits/$A"
req "reserve 1500 (expect 201)" POST /api/v1/limit-reservations "$(body $T-a $A 1500.00)"
req "same reservation again (expect 200, counted once)" POST /api/v1/limit-reservations "$(body $T-a $A 1500)"
req "same transfer, different amount (expect 422 RESERVATION_MISMATCH)" POST /api/v1/limit-reservations "$(body $T-a $A 999)"
req "BR1 above per-transfer cap (expect 422 LIMIT_EXCEEDED)" POST /api/v1/limit-reservations "$(body $T-b $A 100000.01)"
req "release it (expect 204)" DELETE "/api/v1/limit-reservations/$T-a"
req "release again (expect 204)" DELETE "/api/v1/limit-reservations/$T-a"
req "reserve the released transfer again (expect 409 RESERVATION_RELEASED)" POST /api/v1/limit-reservations "$(body $T-a $A 1500)"
req "release unknown (expect 404 RESERVATION_NOT_FOUND)" DELETE "/api/v1/limit-reservations/$T-none"
req "BR3 demo account ACC-2002 (personal limit 2000)" GET /api/v1/limits/ACC-2002
req "  reserve 1500 on ACC-2002 (expect 201)" POST /api/v1/limit-reservations "$(body $T-c ACC-2002 1500)"
req "  reserve 1000 more on ACC-2002 (expect 422 LIMIT_EXCEEDED)" POST /api/v1/limit-reservations "$(body $T-d ACC-2002 1000)"
req "  clean up: release the 1500" DELETE "/api/v1/limit-reservations/$T-c"
