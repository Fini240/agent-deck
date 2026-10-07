#!/bin/bash
# Install/uninstall a per-user LaunchAgent that keeps the helper running at login.
#   install-launchd.sh            install or update (idempotent) and (re)start
#   install-launchd.sh --uninstall
# Only writes ~/Library/LaunchAgents/de.finn.agentdeck.plist. Stopping the helper never
# kills tmux agent sessions; they keep running and are re-adopted on next start.
set -euo pipefail
LABEL="de.finn.agentdeck"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="${AGENTDECK_HOME:-$HOME/.agent-deck}"
DOMAIN="gui/$(id -u)"

if [ "${1:-}" = "--uninstall" ]; then
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "removed $PLIST"
  exit 0
fi

[ -x "$SERVER_DIR/.venv/bin/python" ] || { echo "run scripts/install.sh first" >&2; exit 1; }
mkdir -p "$HOME/Library/LaunchAgents" "$HOME_DIR/logs"
chmod 700 "$HOME_DIR" "$HOME_DIR/logs"
TMP="$(mktemp)"
cat > "$TMP" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>$SERVER_DIR/.venv/bin/python</string><string>-m</string><string>agentdeck</string><string>--log-file</string>
  </array>
  <key>WorkingDirectory</key><string>$SERVER_DIR</string>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>AbandonProcessGroup</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>/dev/null</string>
  <key>StandardErrorPath</key><string>$HOME_DIR/logs/launchd.err</string>
</dict></plist>
PLISTEOF
plutil -lint "$TMP" >/dev/null
if [ -f "$PLIST" ] && cmp -s "$TMP" "$PLIST"; then
  rm -f "$TMP"; echo "plist unchanged"
else
  mv "$TMP" "$PLIST"; chmod 644 "$PLIST"; echo "wrote $PLIST"
fi
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
# bootout returns before launchd always finishes unregistering the old job.
# A direct bootstrap can fail with EIO on a routine upgrade; retry briefly.
BOOTSTRAP_ERROR="$(mktemp)"
STARTED=false
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if launchctl bootstrap "$DOMAIN" "$PLIST" 2>"$BOOTSTRAP_ERROR"; then
    STARTED=true
    break
  fi
  sleep 0.3
done
if [ "$STARTED" != true ]; then
  cat "$BOOTSTRAP_ERROR" >&2
  rm -f "$BOOTSTRAP_ERROR"
  exit 1
fi
rm -f "$BOOTSTRAP_ERROR"
echo "started $LABEL; check: curl -s http://127.0.0.1:18787/health"
