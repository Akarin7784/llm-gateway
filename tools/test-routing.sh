#!/usr/bin/env bash
# Routing verification: does scoring prefer the fast upstream, does the breaker open on a real
# outage, and -- the part that separates a breaker from plain retries -- does traffic then go
# straight to the healthy vendor instead of eating a failure first?
set -uo pipefail
cd "$(dirname "$0")/.."

GATEWAY=${GATEWAY:-http://127.0.0.1:8080/v1}
KEY='Authorization: Bearer sk-alpha-dev'
MOCK_PRIMARY=http://127.0.0.1:9090
n=0

req() {
  n=$((n + 1))
  local upstream
  upstream=$(curl -s -D - -o /dev/null -X POST "$GATEWAY/chat/completions" \
      -H 'Content-Type: application/json' -H "$KEY" \
      -d "{\"model\":\"gw-demo\",\"temperature\":0.7,\"messages\":[{\"role\":\"user\",\"content\":\"routing probe $RANDOM$RANDOM\"}]}" \
      | grep -ai '^x-upstream' | cut -d: -f2 | tr -d '\r ')
  printf '%s ' "${upstream:-none}"
}

degradations() {
  curl -s http://127.0.0.1:8080/actuator/prometheus \
    | grep -a 'gateway_degradation_total{' | awk '{s+=$2} END {print s+0}'
}

echo "=== 1) steady state: scoring between a fast+expensive and a slow+cheap vendor ==="
for i in $(seq 1 6); do req; done; echo

echo "=== 2) primary starts failing; drive enough volume for the window to judge it ==="
curl -s -X POST "$MOCK_PRIMARY/control" -d '{"error_rate":1.0}' >/dev/null
for i in $(seq 1 60); do req; done; echo

echo "=== 3) once the breaker is open, does primary leave the rotation entirely? ==="
before=$(degradations)
for i in $(seq 1 25); do req; done; echo
after=$(degradations)
echo "  failing hops across these 25 requests: $((after - before))"
echo "  (probe attempts are expected and bounded; a plain retry loop would fail on all 25)"

echo "=== 4) what the router says about itself ==="
curl -s "$GATEWAY/routing" -H "$KEY" | tr ',' '\n' | sed 's/^/  /'

echo "=== 5) primary heals; cooldown, probes, recovery ==="
curl -s -X POST "$MOCK_PRIMARY/control" -d '{"error_rate":0.0}' >/dev/null
echo "  waiting out open-duration (10s)"
sleep 11
for i in $(seq 1 6); do req; done; echo
curl -s "$GATEWAY/routing" -H "$KEY" | tr ',' '\n' | sed 's/^/  /'
