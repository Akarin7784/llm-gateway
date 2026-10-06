#!/usr/bin/env bash
# Sustained-stream ladder: how many long-lived responses can one gateway process hold, and what does
# time-to-first-token cost as that number climbs? Short-lived requests would measure request rate,
# which is not what an LLM gateway is judged on.
#
#   tools/bench-stream.sh [chunks] [interval-ms]        # through the gateway
#   DIRECT=1 tools/bench-stream.sh 50 100               # control: same streams straight to the vendor
set -uo pipefail
cd "$(dirname "$0")/.."
source ./tools/env.sh

CHUNKS=${1:-50}
INTERVAL=${2:-100}
CP="target/classes;$(cat tools/cp.txt)"
MODEL=${MODEL:-gw-cheap}   # served by exactly one upstream, so both legs describe the same vendor
GATEWAY=http://127.0.0.1:8080/v1/chat/completions
MOCK=http://127.0.0.1:9090/v1/chat/completions
TARGET=${DIRECT:-0}
URL=$GATEWAY
[[ "$TARGET" == "1" ]] && URL=$MOCK

field() { grep -ao "\"$1\":[0-9.]*" | cut -d: -f2; }

echo "### $( [[ "$TARGET" == "1" ]] && echo 'CONTROL: direct to mock' || echo 'through gateway' )"
echo "### stream profile: $CHUNKS tokens x ${INTERVAL}ms (~$((CHUNKS * INTERVAL / 1000))s per stream)"
echo "concurrency  streams_held  ttft_p50  ttft_p99  total_p99  completed  errors  rps"

for C in 64 256 512 1024; do
  if [[ "$TARGET" != "1" ]]; then
    bash tools/dev-down.sh >/dev/null 2>&1
    bash tools/dev-up.sh >/dev/null 2>&1
  fi

  java -cp "$CP" com.example.llmgw.bench.LoadGenerator \
      --url "$URL" --model "$MODEL" --stream true \
      --mock-chunks "$CHUNKS" --mock-interval-ms "$INTERVAL" \
      --concurrency "$C" --requests "$C" --warmup 8 --timeout-ms 120000 > /tmp/bench-$C.json 2>&1 &
  BENCH_PID=$!

  # Sample the gateway's own gauge rather than counting sockets: the gauge is the number that matters,
  # and netstat on Windows conflates the loopback pairs. Sample after the warmup streams finish.
  sleep $(( CHUNKS * INTERVAL / 1000 + 4 ))
  HELD=-
  [[ "$TARGET" != "1" ]] && HELD=$(curl -fsS http://127.0.0.1:8080/actuator/prometheus 2>/dev/null \
      | awk '/^gateway_streams_active /{print $2}')

  wait $BENCH_PID
  OUT=$(cat /tmp/bench-$C.json)
  printf "%-11s %-13s %-9s %-9s %-10s %-10s %-7s %s\n" \
    "$C" "${HELD:-?}" \
    "$(echo "$OUT" | field ttft_p50_ms)" \
    "$(echo "$OUT" | field ttft_p99_ms)" \
    "$(echo "$OUT" | field p99_ms)" \
    "$(echo "$OUT" | field completed)" \
    "$(echo "$OUT" | field errors)" \
    "$(echo "$OUT" | field rps)"
  [[ "$TARGET" != "1" ]] && bash tools/dev-down.sh >/dev/null 2>&1
  rm -f /tmp/bench-$C.json
done
