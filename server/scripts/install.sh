#!/bin/bash
# Idempotent Agent Deck helper install: venv + dependencies + private ~/.agent-deck.
# Does NOT touch shell startup files, launchd or Tailscale (see install-launchd.sh, tailscale-serve.sh).
set -euo pipefail
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${AGENTDECK_PYTHON:-/Library/Frameworks/Python.framework/Versions/3.13/bin/python3}"
[ -x "$PYTHON" ] || PYTHON="$(command -v python3)"
VENV="$SERVER_DIR/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  "$PYTHON" -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install -q --upgrade pip
"$VENV/bin/python" -m pip install -q -r "$SERVER_DIR/requirements.txt"
if [ "${1:-}" = "--dev" ]; then
  "$VENV/bin/python" -m pip install -q -r "$SERVER_DIR/requirements-dev.txt"
fi

HOME_DIR="${AGENTDECK_HOME:-$HOME/.agent-deck}"
mkdir -p "$HOME_DIR" && chmod 700 "$HOME_DIR"
SA="$HOME_DIR/firebase-service-account.json"
[ -f "$SA" ] && chmod 600 "$SA"
# Creates the local helper token (0600) without printing it.
cd "$SERVER_DIR" && "$VENV/bin/python" -c "from agentdeck import config; config.ensure_local_token()"
echo "Agent Deck helper installed in $VENV (home: $HOME_DIR)"
cd "$SERVER_DIR" && "$VENV/bin/python" -m agentdeck.admin status
