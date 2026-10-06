#!/usr/bin/env bash
# Stops whatever tools/dev-up.sh started.
# Matching on the command line rather than on $!: Git Bash pids are MSYS pids, not Windows pids,
# so a recorded pid cannot be handed to taskkill.
set -uo pipefail
cd "$(dirname "$0")/.."

powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'MockUpstreamServer|com\.example\.llmgw|llm-gateway' } | ForEach-Object { Write-Output ('killing pid ' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }" 2>&1 | sed 's/^/  /'

echo "waiting for ports to release"
for _ in $(seq 1 30); do
  if netstat -ano | grep -E ':(8080|9090|9091)\s.*LISTENING' >/dev/null 2>&1; then
    sleep 1
  else
    echo "stopped"
    exit 0
  fi
done
echo "some ports are still bound" >&2
exit 1
