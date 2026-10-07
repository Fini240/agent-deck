#!/usr/bin/env bash
# Dedicated Docker deployment; never touches other containers, login files or Tailscale routes.
set -euo pipefail
DATA_DIR="${1:-/DATA/AppData/agent-deck}"
PRIVATE_URL="${2:?Usage: install-linux.sh DATA_DIR https://host.tailnet.ts.net:10443 [NAME]}"
HOST_NAME="${3:-agentdeck-host}"
PROJECT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
command -v docker >/dev/null
command -v python3 >/dev/null
mkdir -p "$DATA_DIR/home/.agent-deck" "$DATA_DIR/home/.codex" "$DATA_DIR/workspace" "$DATA_DIR/downloads" "$DATA_DIR/docker-config"
python3 - "$DATA_DIR" "$PRIVATE_URL" <<'PY'
import json, os, sys
from pathlib import Path
from urllib.parse import urlsplit
root=Path(sys.argv[1]).resolve()
url=sys.argv[2].rstrip('/')
p=urlsplit(url)
if p.scheme != 'https' or not p.hostname or p.path or p.query or p.fragment:
    raise SystemExit('Expected a private HTTPS base URL without a path')
state=root/'home'/'.agent-deck'
for name, data in [
 ('config.json', {'publicBaseUrl':url,'workspaceRoots':['/workspace'],'apkDir':'/downloads'}),
 ('settings.json', {'allowedWorkspaces':['/workspace'],'keepAwakeMode':'off'})]:
    file=state/name
    if not file.exists():
        fd=os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f: json.dump(data,f)
# Docker supplies isolation. Its restricted non-root user cannot create the
# namespaces required by Codex's inner bubblewrap sandbox. Seed only new homes;
# preserve explicit administrator preferences on every subsequent installation.
codex=root/'home'/'.codex'/'config.toml'
if not codex.exists():
    fd=os.open(codex,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:
        f.write('sandbox_mode = "danger-full-access"\napproval_policy = "on-request"\n\n'
                '[projects."/workspace"]\ntrust_level = "trusted"\n')
env=root/'runtime.env'
if not env.exists(): env.touch(mode=0o600)
PY
chmod 700 "$DATA_DIR" "$DATA_DIR/home" "$DATA_DIR/home/.agent-deck" "$DATA_DIR/home/.codex" "$DATA_DIR/docker-config"
chmod 600 "$DATA_DIR/runtime.env"
if [ "$(id -u)" = 0 ]; then
  chown 1001:1001 "$DATA_DIR/home" "$DATA_DIR/home/.agent-deck" "$DATA_DIR/home/.codex" "$DATA_DIR/workspace" "$DATA_DIR/downloads" \
    "$DATA_DIR/home/.agent-deck/config.json" "$DATA_DIR/home/.agent-deck/settings.json" "$DATA_DIR/home/.codex/config.toml"
fi
AGENTDECK_DATA_DIR="$DATA_DIR" AGENTDECK_HOST_NAME="$HOST_NAME" DOCKER_CONFIG="$DATA_DIR/docker-config" \
 docker compose -f "$PROJECT_DIR/server/compose.linux.yml" up -d --build
printf 'Helper ready at loopback 18787. Configure private Tailscale Serve, provider logins, FCM and APKs as described in docs/LINUX-HOST.md.\n'
