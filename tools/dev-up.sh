#!/usr/bin/env bash
# Starts two fault-injectable mock upstreams and the gateway. Logs land in logs/.
# The gateway runs from target/classes rather than the fat jar: no file lock, so mvn package
# can run while the stack is up.
set -euo pipefail
cd "$(dirname "$0")/.."
source ./tools/env.sh

mkdir -p logs
if [ ! -d target/classes ] || [ ! -f tools/cp.txt ]; then
  echo "build first: mvn -B -DskipTests package dependency:build-classpath -Dmdep.outputFile=tools/cp.txt" >&2
  exit 1
fi
# The stack reads application.yml from target/classes, so editing the source config without this
# step silently changes nothing. The classpath file is regenerated too: a newly added dependency
# would otherwise surface as a NoClassDefFoundError at startup.
mvn -B -q compile dependency:build-classpath -Dmdep.outputFile=tools/cp.txt \
  || { echo "build step failed" >&2; exit 1; }
CP="target/classes;$(cat tools/cp.txt)"

start() {
  local name="$1"; shift
  ( "$@" > "logs/$name.log" 2>&1 & )
  echo "started $name"
}

start mock-9090 java -cp "$CP" com.example.llmgw.mock.MockUpstreamServer 9090 5
start mock-9091 java -cp "$CP" com.example.llmgw.mock.MockUpstreamServer 9091 60
start gateway java ${GATEWAY_OPTS:-} -cp "$CP" com.example.llmgw.GatewayApplication

echo "waiting for gateway on :8080"
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "ready"
    exit 0
  fi
  sleep 1
done
echo "gateway did not come up; see logs/gateway.log" >&2
exit 1
