#!/bin/bash
# Local admin: pair | devices | revoke ID | apk-link | set-public-url URL | status
set -euo pipefail
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SERVER_DIR"
exec "$SERVER_DIR/.venv/bin/python" -m agentdeck.admin "$@"
