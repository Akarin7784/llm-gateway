#!/usr/bin/env bash
# Acceptance test for upstream cancellation: how long after a client walks away does the gateway
# release the vendor connection? Polls the mock's live stream count on a timeline.
#
#   tools/test-cancellation.sh
set -uo pipefail
cd "$(dirname "$0")/.."

GATEWAY=${GATEWAY:-http://127.0.0.1:8080/v1}
MOCK=${MOCK:-http://127.0.0.1:9090}
KEY=${KEY:-sk-alpha-dev}

body() {
  local mock_json="$1"
  cat <<EOF
{"model":"gw-demo","stream":true,"_mock":$mock_json,"messages":[{"role":"user","content":"cancellation probe"}]}
EOF
}

active() { curl -fsS "$MOCK/stats" | sed -E 's/.*"active_streams":([0-9]+).*/\1/'; }
aborted() { curl -fsS "$MOCK/stats" | sed -E 's/.*"aborted_streams":([0-9]+).*/\1/'; }

run_case() {
  local label="$1" mock_json="$2" max_time="$3" probe_after="$4" settle="$5"
  echo "=== $label ==="
  local a0 b0
  a0=$(active); b0=$(aborted)
  curl -sN --max-time "$max_time" -X POST "$GATEWAY/chat/completions" \
       -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" \
       -d "$(body "$mock_json")" >/dev/null 2>&1 &
  local curl_pid=$!

  ( sleep $((probe_after + 1))
    echo "  t+$((probe_after + 1))s (client gone at ${max_time}s): active=$(active)" ) &
  wait $curl_pid
  sleep "$settle"
  local a1 b1
  a1=$(active); b1=$(aborted)
  echo "  after ${settle}s settle: active=$a0 -> $a1, aborted=$b0 -> $b1"
  echo
}

# Case A: vendor keeps talking. Gateway is writing when the client dies, so it finds out on the
# next chunk -- detection latency is one chunk interval.
run_case "A: chatty upstream (100ms chunks), client aborts at 1s" \
         '{"chunks":200,"chunk_delay_ms":100}' 1 2 3

# Case B: vendor emits one token then goes silent for 8s. Nothing flows through the gateway, so a
# disconnect is only discovered when the silent vendor finally writes again.
run_case "B: upstream silent 8s after first token, client aborts at 1s" \
         '{"chunks":1,"hang_after_first_token_ms":8000}' 1 2 10

echo "gateway counters:"
curl -fsS http://127.0.0.1:8080/actuator/prometheus \
  | grep -E 'gateway_stream_abandoned_total|outcome="abandoned"' || echo "  (none)"
