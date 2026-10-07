#!/bin/bash
# Private HTTPS for the phone via Tailscale Serve on a dedicated port (default 10443).
# Tailnet-only (Serve, NOT Funnel). Leaves the existing :443 route untouched.
#   tailscale-serve.sh            show what would change + current serve status
#   tailscale-serve.sh --apply    configure https:10443 -> http://127.0.0.1:18787
#   tailscale-serve.sh --remove   remove only the 10443 listener
set -euo pipefail
PORT="${AGENTDECK_SERVE_PORT:-10443}"
TARGET="http://127.0.0.1:${AGENTDECK_PORT:-18787}"
TS="$(command -v tailscale || echo /Applications/Tailscale.app/Contents/MacOS/Tailscale)"
case "${1:-}" in
  --apply)  "$TS" serve --bg --https="$PORT" "$TARGET" ;;
  --remove) "$TS" serve --https="$PORT" off ;;
  *) echo "Would run: $TS serve --bg --https=$PORT $TARGET"; echo "(re-run with --apply)"; ;;
esac
"$TS" serve status
