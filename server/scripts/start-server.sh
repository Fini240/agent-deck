#!/bin/bash
# Run the helper in the foreground on 127.0.0.1:18787. Extra args go to `python -m agentdeck`.
set -euo pipefail
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$SERVER_DIR"
exec "$SERVER_DIR/.venv/bin/python" -m agentdeck "$@"
