#!/usr/bin/env bash
# Task 3 evidence: captures the state of the deployment when something is wrong, so the diagnosis
# can be shown in the report. Passwords are masked.
#
#   deploy-scripts/diagnose.sh                 everything
#   deploy-scripts/diagnose.sh account-service focus on one service (logs, inspect, health history)
set -uo pipefail
SVC="${1:-}"
OUT="${OUT_DIR:-evidence/troubleshooting/diagnosis-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
mask() { sed -E 's/(PASSWORD[^=:]*[=:][[:space:]]*)[^ ",]+/\1****/g'; }

{
  echo "== docker compose ps -a   $(date -Is)"
  docker compose ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.Ports}}'
  echo; echo "== Containers that are not healthy"
  docker compose ps -a --format '{{.Service}} {{.Status}}' | grep -Ev 'healthy\)$' || echo "(none)"
} 2>&1 | tee "$OUT/01-ps.txt"

if [[ -n "$SVC" ]]; then
  CID=$(docker compose ps -aq "$SVC")
  {
    echo "== Last 200 log lines of $SVC"; docker compose logs --no-color --tail 200 "$SVC"
  } 2>&1 | mask > "$OUT/02-logs-$SVC.txt"
  echo "== Errors in the log:"; grep -iE 'error|exception|refused|FATAL|unknown host|failed' "$OUT/02-logs-$SVC.txt" | head -20
  {
    echo "== Health check history of $SVC"
    docker inspect --format '{{json .State.Health}}' "$CID"
    echo; echo "== Restart count and exit code"
    docker inspect --format 'restarts={{.RestartCount}} exitCode={{.State.ExitCode}} status={{.State.Status}}' "$CID"
    echo; echo "== Environment the container actually got (passwords masked)"
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CID"
    echo; echo "== Networks and IP"
    docker inspect --format '{{range $n, $c := .NetworkSettings.Networks}}{{$n}} {{$c.IPAddress}}{{println}}{{end}}' "$CID"
  } 2>&1 | mask | tee "$OUT/03-inspect-$SVC.txt"
else
  docker compose logs --no-color --tail 80 2>&1 | mask > "$OUT/02-logs-all.txt"
  echo "== Errors across all services:"; grep -iE 'error|exception|refused|FATAL|unknown host|failed' "$OUT/02-logs-all.txt" | head -30
fi

{
  echo; echo "== Can each service be reached by name inside the network? (from transfer-service)"
  for t in account-service:8081 ledger-service:8083 limits-service:8084 notification-service:8085; do
    printf "  %-26s " "$t"; docker compose exec -T transfer-service curl -s -m 3 -o /dev/null -w "%{http_code}\n" "http://$t/health" 2>&1 || echo "not reachable"
  done
  echo; echo "== Volumes (a database keeps its first password in its volume)"
  docker volume ls --filter name=money-transfer
} 2>&1 | tee "$OUT/04-network-volumes.txt"

echo; echo "Saved under $OUT"
