#!/usr/bin/env bash
# Switch the simulated provider without restarting (condition e: a dependency that can be slow or fail).
#   scripts/provider.sh ok             normal
#   scripts/provider.sh down           every send fails
#   scripts/provider.sh flaky [0.5]    a share of sends fail at random
#   scripts/provider.sh slow [5000]    every send waits (ms); above 3000 the Transfer service times out
#   scripts/provider.sh fail-next [2]  the next N sends fail, then it works again
#   scripts/provider.sh show
set -euo pipefail
BASE="${NOTIFICATION_URL:-http://localhost:8085}"
put() { curl -s -X PUT -H "Content-Type: application/json" -d "$1" "$BASE/api/v1/admin/provider"; echo; }
case "${1:-show}" in
  ok)        put '{"delayMs":0,"failRate":0,"failNext":0}' ;;
  down)      put '{"delayMs":0,"failRate":1,"failNext":0}' ;;
  flaky)     put "{\"delayMs\":0,\"failRate\":${2:-0.5},\"failNext\":0}" ;;
  slow)      put "{\"delayMs\":${2:-5000},\"failRate\":0,\"failNext\":0}" ;;
  fail-next) put "{\"delayMs\":0,\"failRate\":0,\"failNext\":${2:-2}}" ;;
  show)      curl -s "$BASE/api/v1/admin/provider"; echo ;;
  *) echo "usage: $0 ok|down|flaky [rate]|slow [ms]|fail-next [n]|show" >&2; exit 1 ;;
esac
