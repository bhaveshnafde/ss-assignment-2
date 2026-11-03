#!/usr/bin/env bash
# Quick check of every Notification path.
set -uo pipefail

BASE="${NOTIFICATION_URL:-http://localhost:8085}"
T="smoke-$(date +%s)"

req() { # $1 label, $2 method, $3 path, $4 body (optional)
  echo "-- $1"
  if [[ -n "${4:-}" ]]; then
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" -H "Content-Type: application/json" -d "$4" "$BASE$3"
  else
    curl -s -w "\n   [HTTP %{http_code}]\n" -X "$2" "$BASE$3"
  fi
}
body() { echo "{\"transferId\":\"$1\",\"accountId\":\"ACC-1001\",\"channel\":\"SMS\",\"template\":\"TRANSFER_COMPLETED\",\"message\":\"$2\"}"; }
provider() { curl -s -o /dev/null -X PUT -H "Content-Type: application/json" -d "$1" "$BASE/api/v1/admin/provider"; }

provider '{"delayMs":0,"failRate":0,"failNext":0}'
req "health" GET /health
req "notify (expect 202 SENT)" POST /api/v1/notifications "$(body $T-a 'You sent 100.00 INR')"
req "same again (expect 202, nothing sent twice)" POST /api/v1/notifications "$(body $T-a 'You sent 100.00 INR')"
req "same transfer, different message (expect 422 NOTIFICATION_MISMATCH)" POST /api/v1/notifications "$(body $T-a 'You sent 999.00 INR')"
req "unknown channel (expect 400 MALFORMED_REQUEST)" POST /api/v1/notifications \
  "{\"transferId\":\"$T-x\",\"accountId\":\"ACC-1001\",\"channel\":\"PIGEON\",\"template\":\"TRANSFER_COMPLETED\",\"message\":\"hi\"}"
req "bad template (expect 422 INVALID_TEMPLATE)" POST /api/v1/notifications \
  "{\"transferId\":\"$T-y\",\"accountId\":\"ACC-1001\",\"channel\":\"SMS\",\"template\":\"lower-case\",\"message\":\"hi\"}"
echo "== provider: next 2 sends fail"
provider '{"delayMs":0,"failRate":0,"failNext":2}'
req "  attempt 1 (expect 503 PROVIDER_DOWN)" POST /api/v1/notifications "$(body $T-b 'You sent 1.00 INR')"
req "  attempt 2 (expect 503 PROVIDER_DOWN)" POST /api/v1/notifications "$(body $T-b 'You sent 1.00 INR')"
req "  attempt 3 (expect 202 SENT, attempts 3)" POST /api/v1/notifications "$(body $T-b 'You sent 1.00 INR')"
req "  history (FAILED, FAILED, SENT)" GET "/api/v1/notifications?transferId=$T-b"
req "query without parameters (expect 400 MISSING_PARAMETER)" GET /api/v1/notifications
req "invalid setting (expect 422 INVALID_SETTING)" PUT /api/v1/admin/provider '{"delayMs":0,"failRate":1.5,"failNext":0}'
req "provider settings" GET /api/v1/admin/provider
