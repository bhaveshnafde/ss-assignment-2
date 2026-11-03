#!/usr/bin/env bash
# Task 2: stop the whole deployment, start it again, and show it still works and kept its data.
set -uo pipefail
OUT="${OUT_DIR:-evidence/deployment/restart-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
{
  echo "== Balance of ACC-1001 before the restart"
  curl -s http://localhost:8081/api/v1/accounts/ACC-1001; echo
  echo; echo "== docker compose stop"; docker compose stop
  echo; echo "== docker compose ps (all stopped)"; docker compose ps -a --format 'table {{.Service}}\t{{.Status}}'
  echo; echo "== docker compose up -d (no rebuild), then wait until healthy"
  docker compose up -d --wait
  docker compose ps --format 'table {{.Service}}\t{{.Status}}'
  echo; echo "== Balance after the restart (same as before: data lives in the volumes)"
  curl -s http://localhost:8081/api/v1/accounts/ACC-1001; echo
} 2>&1 | tee "$OUT/restart.txt"
echo; echo "Now run deploy-scripts/verify-deployment.sh to show the workflow works again."
