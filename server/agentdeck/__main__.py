"""``python -m agentdeck`` — run the helper server on 127.0.0.1 only."""

from __future__ import annotations

import argparse
import logging
import sys

import uvicorn

from . import __version__
from . import config as cfgmod
from .app import create_app


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="agentdeck", description="Agent Deck Mac helper server")
    p.add_argument("--port", type=int, help=f"local port (default {cfgmod.DEFAULT_PORT})")
    p.add_argument("--log-level", default="info", choices=["debug", "info", "warning", "error"])
    p.add_argument("--log-file", action="store_true", help="log to ~/.agent-deck/logs/server.log (rotated, 3 x 1 MB)")
    args = p.parse_args(argv)

    fmt = "%(asctime)s %(levelname)s %(name)s: %(message)s"
    if args.log_file:
        from logging.handlers import RotatingFileHandler

        from .storage import ensure_private_dir

        log_dir = ensure_private_dir(cfgmod.home_dir() / "logs")
        handler = RotatingFileHandler(log_dir / "server.log", maxBytes=1_000_000, backupCount=3)
        handler.setFormatter(logging.Formatter(fmt))
        logging.basicConfig(level=args.log_level.upper(), handlers=[handler])
        (log_dir / "server.log").chmod(0o600)
    else:
        logging.basicConfig(level=args.log_level.upper(), format=fmt)
    scfg = cfgmod.load_server_config()
    if args.port:
        scfg.port = args.port
    app = create_app(server_config=scfg)
    logging.getLogger("agentdeck").info("agent-deck %s listening on %s:%s", __version__, cfgmod.BIND_HOST, scfg.port)
    uvicorn.run(
        app,
        host=cfgmod.BIND_HOST,  # loopback only; Tailscale Serve provides private HTTPS
        port=scfg.port,
        access_log=False,  # paths may contain download tokens
        proxy_headers=False,
        server_header=False,
        log_level=args.log_level,
        log_config=None,  # use the root logging configured above
        timeout_graceful_shutdown=5,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
