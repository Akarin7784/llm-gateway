#!/usr/bin/env bash
# Measures how long the gateway takes to notice that its client left while the vendor is silent.
# The vendor holds the stream open and emits nothing, so only a gateway-side write can surface it.
#
#   tools/measure-detection.sh 2s
#   tools/measure-detection.sh 0s 9000      # heartbeat disabled
set -uo pipefail
cd "$(dirname "$0")/.."
source ./tools/env.sh

INTERVAL=${1:-2s}
HANG_MS=${2:-9000}
MOCK=${MOCK:-http://127.0.0.1:9090}

echo "### heartbeat-interval=$INTERVAL vendor_silence=${HANG_MS}ms"

bash tools/dev-down.sh >/dev/null 2>&1
GATEWAY_OPTS="-Dgateway.stream-heartbeat-interval=$INTERVAL" bash tools/dev-up.sh >/dev/null 2>&1

active() { curl -fsS "$MOCK/stats" | sed -E 's/.*"active_streams":([0-9]+).*/\1/'; }

attempt=0
while [ "$(active)" != "0" ] && [ $attempt -lt 30 ]; do
  sleep 0.5
  attempt=$((attempt + 1))
done

# Fire the request, walk away after 1s, then poll for the moment the vendor connection is released.
START=$(date +%s%3N)
curl -sN --max-time 1 -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-alpha-dev' \
  -d "{\"model\":\"gw-demo\",\"stream\":true,\"_mock\":{\"chunks\":1,\"hang_after_first_token_ms\":$HANG_MS},\"messages\":[{\"role\":\"user\",\"content\":\"probe\"}]}" \
  >/dev/null 2>&1 &

# Wait until the stream is actually registered before looking for its release.
registered=0
for _ in $(seq 1 200); do
  if [ "$(active)" = "1" ]; then
    registered=1
    break
  fi
done
if [ $registered -eq 0 ]; then
  echo "  stream never registered with the mock; cannot measure"
  exit 1
fi

released=""
for _ in $(seq 1 $(( (HANG_MS + 3000) / 50 ))); do
  sleep 0.05
  if [ "$(active)" = "0" ]; then
    released=$(date +%s%3N)
    break
  fi
done

if [ -z "$released" ]; then
  echo "  never released while the vendor stayed silent (>${HANG_MS}ms)"
else
  echo "  client left at 1000ms; upstream released at $((released - START))ms"
  echo "  detection latency after departure: $((released - START - 1000))ms"
fi
