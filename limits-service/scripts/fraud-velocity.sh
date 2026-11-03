#!/usr/bin/env bash
# F1 fraud rule: more than VELOCITY_MAX_TRANSFERS (default 10) transfers from one account within
# VELOCITY_WINDOW_SECONDS (default 60) are refused with VELOCITY_LIMIT.
#   scripts/fraud-velocity.sh           (sends 12 small reservations one after another)
set -euo pipefail
BASE="${LIMITS_URL:-http://localhost:8084}"
COUNT="${COUNT:-12}"
A="ACC-FRAUD-$(date +%s)"
OUT="${OUT_DIR:-evidence/limits-velocity/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
{
  echo "== Velocity (fraud) rule   $(date -Is)"
  echo "Sending $COUNT reservations of 1.00 from $A as fast as possible:"
  for i in $(seq 1 "$COUNT"); do
    code=$(curl -s -o "$OUT/r-$i.json" -w "%{http_code}" -H "Content-Type: application/json" \
      -d "{\"transferId\":\"$A-t$i\",\"accountId\":\"$A\",\"amount\":1.00,\"currency\":\"INR\"}" "$BASE/api/v1/limit-reservations")
    echo "  #$i -> $code $(grep -o '"code":"[^"]*"' "$OUT/r-$i.json" || true)"
  done
  echo
  curl -s "$BASE/api/v1/limits/$A" | tee "$OUT/limits-after.json"; echo
} | tee "$OUT/summary.txt"
