#!/usr/bin/env bash
# Switch the Ledger's demo faults at runtime.
#   scripts/fault.sh on          POST /journals answers 503 (Challenge 3C: saga fails at step 3)
#   scripts/fault.sh slow 5000   answer 5 s after saving (above Transfer's 3 s timeout: retry demo)
#   scripts/fault.sh off         back to normal
#   scripts/fault.sh             show current settings
set -euo pipefail
BASE="${LEDGER_URL:-http://localhost:8083}"
put() { curl -s -X PUT -H "Content-Type: application/json" -d "$1" "$BASE/api/v1/admin/faults"; echo; }
case "${1:-show}" in
  on)   put '{"failPosts":true,"postDelayMs":0}' ;;
  slow) put "{\"failPosts\":false,\"postDelayMs\":${2:-5000}}" ;;
  off)  put '{"failPosts":false,"postDelayMs":0}' ;;
  *)    curl -s "$BASE/api/v1/admin/faults"; echo ;;
esac
