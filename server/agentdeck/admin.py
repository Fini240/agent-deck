"""Local administration CLI (runs on the Mac only, never over the network).

    python -m agentdeck.admin pair [--server URL] [--ttl 300] [--svg FILE] [--json]
    python -m agentdeck.admin devices
    python -m agentdeck.admin revoke DEVICE_ID
    python -m agentdeck.admin apk-link [--ttl 1800]
    python -m agentdeck.admin set-public-url URL
    python -m agentdeck.admin status

Works whether or not the server is running: pairing codes and links are written
to 0600 files in ~/.agent-deck that the server reads on each request.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from . import __version__
from . import config as cfgmod
from .auth import DeviceStore, pairing_uri
from .downloads import DownloadTokens, find_apks
from .push import FcmSender
from .storage import write_private_bytes


def resolve_base_url(explicit: str | None = None) -> str:
    if explicit:
        url = explicit.rstrip("/")
    else:
        url = cfgmod.load_server_config().public_base_url or cfgmod.detect_tailscale_base_url() or ""
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname:
        raise SystemExit(
            "No private HTTPS base URL. Pass --server https://<mac>.<tailnet>.ts.net:10443 "
            "or run: python -m agentdeck.admin set-public-url URL"
        )
    return url


def _print_qr(data: str, svg: str | None) -> None:
    try:
        import segno
    except ImportError:
        print("(install segno for a terminal QR code)")
        return
    qr = segno.make(data, error="m")
    qr.terminal(compact=True)
    if svg:
        import io

        buf = io.BytesIO()
        qr.save(buf, kind="svg", scale=8, border=4)
        write_private_bytes(Path(svg).expanduser(), buf.getvalue())
        print(f"QR saved to {svg} (mode 0600)")


def _fmt_ts(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).astimezone().strftime("%H:%M:%S")


def cmd_pair(args) -> int:
    base = resolve_base_url(args.server)
    store = DeviceStore(cfgmod.home_dir())
    code, expires = store.create_pairing_code(args.ttl)
    uri = pairing_uri(base, code)
    if args.json:
        print(json.dumps({"uri": uri, "server": base, "code": code, "expiresAt": expires}))
        return 0
    print("Scan with Agent Deck on the phone (single use, expires at", _fmt_ts(expires) + "):\n")
    _print_qr(uri, args.svg)
    print(f"\nServer: {base}\nCode:   {code[:4]}-{code[4:8]}-{code[8:]}")
    return 0


def cmd_devices(_args) -> int:
    devices = DeviceStore(cfgmod.home_dir()).list_devices()
    if not devices:
        print("No paired devices.")
    for d in devices:
        push = "push" if d.get("fcmToken") else "no-push"
        print(f"{d['deviceId']}  {d.get('name','?'):<24} paired {d.get('createdAt')}  last seen {d.get('lastSeenAt') or '-'}  {push}")
    return 0


def cmd_revoke(args) -> int:
    ok = DeviceStore(cfgmod.home_dir()).revoke(args.device_id)
    print("revoked" if ok else "no such device")
    return 0 if ok else 1


def cmd_apk_link(args) -> int:
    base = resolve_base_url(args.server)
    apks = find_apks(cfgmod.load_server_config().resolved_apk_dir(), with_hash=False)
    if not apks:
        print(f"Note: no APK in {cfgmod.load_server_config().resolved_apk_dir()} yet.")
    token, expires = DownloadTokens(cfgmod.home_dir()).create(args.ttl)
    url = f"{base}/download/{token}"
    print("Open on the phone (tailnet only, expires at", _fmt_ts(expires) + "):\n")
    _print_qr(url, None)
    print("\n" + url)
    return 0


def cmd_set_public_url(args) -> int:
    url = args.url.rstrip("/")
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.path not in ("", "/"):
        raise SystemExit("URL must look like https://host.tailnet.ts.net:10443")
    cfgmod.save_server_config_value("publicBaseUrl", url)
    print("saved; restart the server so the Host allowlist picks it up")
    return 0


def cmd_status(_args) -> int:
    scfg = cfgmod.load_server_config()
    home = cfgmod.home_dir()
    fcm = FcmSender(scfg.service_account_path(), scfg.fcm.project_id, scfg.fcm.enabled).status()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{scfg.port}/health", timeout=2) as r:
            running = json.load(r).get("status") == "ok"
    except Exception:
        running = False
    print(f"agent-deck {__version__}")
    print(f"home:        {home}")
    print(f"server:      127.0.0.1:{scfg.port} ({'running' if running else 'not running'})")
    print(f"public URL:  {scfg.public_base_url or cfgmod.detect_tailscale_base_url() or 'not configured'}")
    print(f"devices:     {len(DeviceStore(home).list_devices())}")
    print(f"push:        {'available' if fcm.available else 'unavailable: ' + str(fcm.reason)}"
          + (f" (project {fcm.project_id})" if fcm.project_id else ""))
    print(f"APK dir:     {scfg.resolved_apk_dir()}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="agentdeck-admin", description="Agent Deck local administration")
    sub = p.add_subparsers(dest="cmd", required=True)
    sp = sub.add_parser("pair", help="create a single-use pairing QR code")
    sp.add_argument("--server", help="private base URL, e.g. https://mac.tailnet.ts.net:10443")
    sp.add_argument("--ttl", type=int, default=300)
    sp.add_argument("--svg", help="also write the QR code as SVG (0600)")
    sp.add_argument("--json", action="store_true", help="machine-readable output (contains the code)")
    sp.set_defaults(fn=cmd_pair)
    sub.add_parser("devices", help="list paired devices").set_defaults(fn=cmd_devices)
    sr = sub.add_parser("revoke", help="revoke a device")
    sr.add_argument("device_id")
    sr.set_defaults(fn=cmd_revoke)
    sa = sub.add_parser("apk-link", help="create an expiring private APK download link")
    sa.add_argument("--server")
    sa.add_argument("--ttl", type=int, default=1800)
    sa.set_defaults(fn=cmd_apk_link)
    su = sub.add_parser("set-public-url", help="store the Tailscale Serve base URL")
    su.add_argument("url")
    su.set_defaults(fn=cmd_set_public_url)
    sub.add_parser("status", help="show configuration and push availability").set_defaults(fn=cmd_status)
    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
