#!/usr/bin/env bash
# Live quota verification: does the gateway actually stop traffic at the configured ceiling, and does
# it say so in a way a client can act on?
#
#   tools/test-quota.sh
set -uo pipefail
cd "$(dirname "$0")/.."

GATEWAY=${GATEWAY:-http://127.0.0.1:8080/v1}
# tenant-beta is configured rpm=30, tpm=50000, max-concurrency=8
KEY=${KEY:-sk-beta-dev}

# Fast upstream: the point is the admission decision, not vendor latency.
BODY_NONSTREAM='{"model":"gw-demo","_mock":{"chunks":1,"chunk_delay_ms":0,"first_token_delay_ms":0},"messages":[{"role":"user","content":"quota probe"}]}'

status_of() {
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/chat/completions" \
       -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" -d "$BODY_NONSTREAM"
}

echo "=== A) rpm ceiling: 60 sequential requests against rpm=30 ==="
codes=$(for i in $(seq 1 60); do status_of; done)
ok=$(echo "$codes" | grep -c '^200$' || true)
limited=$(echo "$codes" | grep -c '^429$' || true)
first429=$(echo "$codes" | grep -n '^429$' | head -1 | cut -d: -f1)
echo "  200=$ok  429=$limited  first rejection at request #$first429"

echo "=== B) the rejection is actionable ==="
tmp=${TMPDIR:-.}/quota-probe.$$
: > "$tmp"
for i in $(seq 1 60); do
  curl -s -D - -o /dev/null -X POST "$GATEWAY/chat/completions" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" -d "$BODY_NONSTREAM" >> "$tmp" 2>&1
  grep -qa '^HTTP/1.1 429' "$tmp" && break
  : > "$tmp"
done
grep -aiE '^HTTP/|^retry-after' "$tmp" || echo "  (never saw a 429 to inspect)"
rm -f "$tmp"

echo "=== C) in-flight ceiling: 20 concurrent against max-concurrency=4 (tenant-gamma) ==="
# Gamma has a roomy rpm, so whatever gets refused here is refused by the concurrency gate.
GAMMA='sk-gamma-dev'
SLOW='{"model":"gw-demo","_mock":{"chunks":40,"chunk_delay_ms":50},"messages":[{"role":"user","content":"slow"}]}'
# One file per caller: appending 20 concurrent writes to a single file interleaves the status codes.
outdir=$(mktemp -d)
for i in $(seq 1 20); do
  curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/chat/completions" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $GAMMA" -d "$SLOW" > "$outdir/$i" 2>&1 &
done
wait
echo "  200=$(grep -l '^200' "$outdir"/* 2>/dev/null | wc -l)  429=$(grep -l '^429' "$outdir"/* 2>/dev/null | wc -l)"
echo "  expected: 4 admitted (the cap), 16 refused immediately by the concurrency gate"
rm -rf "$outdir"

echo "=== D) the gate recovers: leases are given back, not leaked ==="
sleep 3
after=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/chat/completions" \
        -H 'Content-Type: application/json' -H "Authorization: Bearer $GAMMA" -d "$SLOW")
echo "  one request after the burst: $after (200 means nothing was stranded by the 16 refusals)"

echo "=== E) rejection reasons per dimension ==="
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -a -E 'gateway_quota_(rejected|reserved|refunded|overage|unreconciled)' | head -12
