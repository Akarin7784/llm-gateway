#!/usr/bin/env bash
# Cache behaviour checks. Each one corresponds to a claim the implementation makes, so a regression
# shows up as a failing line rather than as a subtly wrong hit ratio a week later.
#
#   tools/test-cache.sh
set -uo pipefail
cd "$(dirname "$0")/.."

GATEWAY=${GATEWAY:-http://127.0.0.1:8080/v1}
ALPHA='sk-alpha-dev'
BETA='sk-beta-dev'

probe() {
  local key="$1" body="$2" label="$3"
  local headers
  headers=$(curl -s -D - -o /dev/null -X POST "$GATEWAY/chat/completions" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $key" -d "$body" \
      -w 'TIME=%{time_total}\n' | grep -aiE '^x-cache|^x-upstream|^TIME=')
  printf '  %-22s %s\n' "$label" "$(echo "$headers" | tr -d '\r' | paste -sd' ' -)"
}

PROMPT='{"model":"gw-demo","messages":[{"role":"user","content":"cache identity probe"}]}'
STREAM_PROMPT='{"model":"gw-demo","stream":true,"messages":[{"role":"user","content":"cache identity probe"}]}'
TEMP_PROMPT='{"model":"gw-demo","temperature":0.9,"messages":[{"role":"user","content":"cache identity probe"}]}'
OTHER_TENANT_SAME_PROMPT="$PROMPT"

echo "=== 1) second identical call is served from cache, without touching a vendor ==="
probe "$ALPHA" "$PROMPT" "first (expect MISS)"
probe "$ALPHA" "$PROMPT" "second (expect HIT)"

echo "=== 2) transport is not part of the identity: stream flag hits the blocking entry ==="
probe "$ALPHA" "$STREAM_PROMPT" "stream flag (expect HIT)"

echo "=== 3) gateway control fields do not split the cache ==="
probe "$ALPHA" '{"model":"gw-demo","_mock":{"chunks":9},"messages":[{"role":"user","content":"cache identity probe"}]}' "with _mock (expect HIT)"

echo "=== 4) sampling is never cached ==="
probe "$ALPHA" "$TEMP_PROMPT" "temperature=0.9 #1"
probe "$ALPHA" "$TEMP_PROMPT" "temperature=0.9 #2"

echo "=== 5) tenant isolation: beta does not read alpha's entry ==="
probe "$BETA" "$OTHER_TENANT_SAME_PROMPT" "beta same prompt (expect MISS)"
probe "$BETA" "$OTHER_TENANT_SAME_PROMPT" "beta again (expect HIT)"

echo "=== 6) what the cache reports about itself ==="
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -a -E 'gateway_cache_(lookup|saved|entries|hit_ratio)' | grep -av '^#'
