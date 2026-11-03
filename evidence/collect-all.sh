#!/usr/bin/env bash
# Fills the /evidence folder from the RUNNING system. Run from the repository root:
#
#   evidence/collect-all.sh            every challenge (Account must run with the default atomic holds)
#   evidence/collect-all.sh naive      only the Challenge 1 "before" run; restart Account with
#                                      HOLD_STRATEGY=naive first, and back to normal afterwards
#   RUN_TESTS=1 evidence/collect-all.sh   also runs `mvn verify` in each service and keeps the summary
#
# Needs: bash (Git Bash on Windows), curl, and all five services running on their default ports.
# Re-running overwrites the previous results, so the links in the READMEs always point at the latest run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EV="$ROOT/evidence"
cd "$ROOT"

step() { echo; echo "################ $*"; }
fresh() { rm -rf "$1"; mkdir -p "$1"; }

check_up() {
  local ok=1
  for s in transfer:8082 account:8081 ledger:8083 limits:8084 notification:8085; do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${s#*:}/health")
    printf "  %-13s %s\n" "${s%%:*}" "$([[ $code == 200 ]] && echo UP || echo "DOWN ($code)")"
    [[ $code == 200 ]] || ok=0
  done
  [[ $ok == 1 ]] || { echo "Start every service first (see each service's README)."; exit 1; }
}

run_info() {
  {
    echo "Collected: $(date -Is)"
    echo "Git commit: $(git rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout')"
    echo "Machine: $(uname -s) $(uname -m)"
    echo
    echo "Service health at collection time:"
    for p in 8082 8081 8083 8084 8085; do echo "  :$p $(curl -s http://localhost:$p/health)"; done
  } > "$EV/run-info.txt"
}

if [[ "${1:-}" == "naive" ]]; then
  step "Challenge 1, naive control run (Account must be running with HOLD_STRATEGY=naive)"
  fresh "$EV/challenge-1-concurrency/naive"
  LABEL=naive OUT_DIR="$EV/challenge-1-concurrency/naive" bash account-service/scripts/challenge1-concurrency.sh
  echo; echo "Done. Restart the Account service WITHOUT HOLD_STRATEGY=naive before anything else."
  exit 0
fi

step "Checking the services"
check_up
run_info

step "Challenge 1: 25 parallel holds against one balance"
fresh "$EV/challenge-1-concurrency/atomic"
OUT_DIR="$EV/challenge-1-concurrency/atomic" bash account-service/scripts/challenge1-concurrency.sh

step "Challenge 1: the same race on a daily limit (Limits)"
fresh "$EV/challenge-1-concurrency/daily-limit"
OUT_DIR="$EV/challenge-1-concurrency/daily-limit" bash limits-service/scripts/concurrency.sh

step "Challenge 1: fraud rule F1 (velocity)"
fresh "$EV/challenge-1-concurrency/velocity"
OUT_DIR="$EV/challenge-1-concurrency/velocity" bash limits-service/scripts/fraud-velocity.sh

step "Challenge 2: idempotency of POST /api/v1/transfers"
fresh "$EV/challenge-2-idempotency/transfer"
OUT_DIR="$EV/challenge-2-idempotency/transfer" bash transfer-service/scripts/challenge2-idempotency.sh

step "Challenge 2: downstream calls are idempotent too (Ledger and Notification)"
fresh "$EV/challenge-2-idempotency/ledger-parallel-post"
OUT_DIR="$EV/challenge-2-idempotency/ledger-parallel-post" bash ledger-service/scripts/parallel-post.sh
fresh "$EV/challenge-2-idempotency/notification-parallel"
OUT_DIR="$EV/challenge-2-idempotency/notification-parallel" bash notification-service/scripts/parallel-notify.sh

step "Challenge 3C: Ledger forced to fail at step 3"
fresh "$EV/challenge-3c-saga/ledger-failure"
OUT_DIR="$EV/challenge-3c-saga/ledger-failure" bash transfer-service/scripts/challenge3c-saga-failure.sh
TID=$(grep -o '"transferId":"[^"]*"' "$EV/challenge-3c-saga/ledger-failure/response.body.json" | head -1 | cut -d'"' -f4)
if [[ -n "$TID" ]]; then
  echo; echo "== Limit reservation for $TID (expect RELEASED)"
  curl -s "http://localhost:8084/api/v1/limit-reservations/$TID" | tee "$EV/challenge-3c-saga/ledger-failure/limit-reservation.json"; echo
fi

step "Dependency failure: SMS provider fails the next 2 sends"
D="$EV/dependency-failure/sms-fail-next-2"; fresh "$D"
{
  echo "== SMS provider fails the next 2 sends   $(date -Is)"
  curl -s -X PUT -H "Content-Type: application/json" -d '{"delayMs":0,"failRate":0,"failNext":2}' \
    http://localhost:8085/api/v1/admin/provider | tee "$D/provider-settings.json"; echo
  KEY="evidence-sms-$(date +%s)"
  curl -s -D "$D/transfer.headers.txt" -o "$D/transfer.json" -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" -H "X-Request-ID: $KEY" \
    -d '{"sourceAccountId":"ACC-2002","destinationAccountId":"ACC-1001","amount":10.00,"currency":"INR"}' \
    http://localhost:8082/api/v1/transfers
  echo "Transfer answer: $(head -1 "$D/transfer.headers.txt" | tr -d '\r')"; cat "$D/transfer.json"; echo
  TID=$(grep -o '"transferId":"[^"]*"' "$D/transfer.json" | head -1 | cut -d'"' -f4)
  echo "Waiting 8 s for Transfer's background retries..."; sleep 8
  curl -s "http://localhost:8085/api/v1/notifications?transferId=$TID" | tee "$D/notifications.json"; echo
  curl -s "http://localhost:8082/api/v1/transfers/$TID" | tee "$D/transfer-after.json"; echo
  curl -s "http://localhost:8082/api/v1/transfers/$TID/saga-steps" > "$D/saga-steps.json"
  echo "Outcomes in order: $(grep -o '"outcome":"[A-Z]*"' "$D/notifications.json" | cut -d'"' -f4 | tr '\n' ' ')"
  curl -s -X PUT -H "Content-Type: application/json" -d '{"delayMs":0,"failRate":0,"failNext":0}' \
    http://localhost:8085/api/v1/admin/provider > /dev/null
  echo "Provider back to normal."
} | tee "$D/summary.txt"

if [[ "${RUN_TESTS:-0}" == "1" ]]; then
  step "Automated tests (mvn verify) per service"
  mkdir -p "$EV/tests"; rm -f "$EV/tests"/*.log "$EV/tests"/*-tests.txt "$EV/tests/summary.txt"
  for s in transfer account ledger limits notification ui; do
    [[ -d "$s-service" ]] || continue
    echo "-- $s-service"
    (cd "$s-service" && mvn -q verify > "$EV/tests/$s-service-mvn-verify.log" 2>&1); rc=$?
    echo "$s-service: $([[ $rc == 0 ]] && echo PASSED || echo "FAILED (exit $rc)")" | tee -a "$EV/tests/summary.txt"
    grep -h "Tests run:" "$s-service"/target/surefire-reports/*.txt 2>/dev/null >> "$EV/tests/$s-service-tests.txt"
  done
fi

echo; echo "All evidence written under evidence/. Open evidence/README.md for what each file proves."
