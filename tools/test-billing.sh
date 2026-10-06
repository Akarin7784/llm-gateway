#!/usr/bin/env bash
# Billing reconciliation: drive a workload that touches every priced outcome, then ask the gateway to
# prove its own books. The two equations under "equations" must read delta=0.
set -uo pipefail
cd "$(dirname "$0")/.."

GATEWAY=${GATEWAY:-http://127.0.0.1:8080/v1}
KEY=${KEY:-'Authorization: Bearer sk-alpha-dev'}

send() {
  curl -s -o /dev/null -X POST "$GATEWAY/chat/completions" -H 'Content-Type: application/json' -H "$KEY" -d "$1"
}

echo "=== workload ==="
echo "  12 executed calls (temperature=0.7 so nothing is cached)"
for i in $(seq 1 12); do
  send '{"model":"gw-demo","temperature":0.7,"_mock":{"chunks":3,"chunk_delay_ms":5},"messages":[{"role":"user","content":"billing probe '"$i"'"}]}'
done

echo "  4 identical calls -> 1 executed + 3 cached"
for i in $(seq 1 4); do
  send '{"model":"gw-demo","_mock":{"chunks":2,"chunk_delay_ms":5},"messages":[{"role":"user","content":"identical billing probe"}]}'
done

echo "  3 streamed calls to completion"
for i in $(seq 1 3); do
  curl -sN -o /dev/null -X POST "$GATEWAY/chat/completions" -H 'Content-Type: application/json' -H "$KEY" \
    -d '{"model":"gw-demo","stream":true,"_mock":{"chunks":4,"chunk_delay_ms":10},"messages":[{"role":"user","content":"stream billing probe '"$i"'"}]}'
done

echo "  1 abandoned stream (client leaves after 1s of a 6s stream)"
curl -sN --max-time 1 -o /dev/null -X POST "$GATEWAY/chat/completions" -H 'Content-Type: application/json' -H "$KEY" \
  -d '{"model":"gw-demo","stream":true,"_mock":{"chunks":60,"chunk_delay_ms":100},"messages":[{"role":"user","content":"abandon me"}]}'

echo "  1 rejected call (no api key) -> must not appear in the ledger at all"
curl -s -o /dev/null -X POST "$GATEWAY/chat/completions" -H 'Content-Type: application/json' -d '{"model":"gw-demo","messages":[]}'

sleep 2

echo
echo "=== reconcile ==="
curl -s "$GATEWAY/billing/reconcile" -H "$KEY" | sed 's/},{/}\n  {/g; s/,\"/,\n  \"/g' | head -40

echo
echo "=== summary (this tenant) ==="
curl -s "$GATEWAY/billing/summary" -H "$KEY" | head -c 500; echo
