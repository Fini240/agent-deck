"""Read-only status queries against the local Codex app-server daemon.

Codex 0.160 terminal TUIs run their threads inside a shared app-server daemon
(``codex app-server --listen unix:// --managed-daemon``). Its control socket
(``~/.codex/app-server-control/app-server-control.sock``) speaks JSON-RPC over
a WebSocket. We only call ``initialize``, ``thread/loaded/list`` and
``thread/read`` (metadata only, ``includeTurns=false``). These are read-only and
never start a turn or use model quota. We never subscribe, resume or write.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
import threading
import time
from pathlib import Path
from typing import Any

from .common import codex_home

_lock = threading.Lock()
_cache: dict[str, Any] = {"at": 0.0, "value": None}


def control_socket() -> Path | None:
    p = Path(os.environ.get("AGENTDECK_CODEX_CONTROL_SOCK") or codex_home() / "app-server-control" / "app-server-control.sock")
    try:
        real = p.resolve()
    except OSError:
        return None
    return real if real.exists() else None


class _WS:
    def __init__(self, path: Path, timeout: float) -> None:
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect(str(path))
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("control socket closed during handshake")
            head += chunk
            if len(head) > 16384:
                raise ConnectionError("handshake too large")
        status, _, rest = head.partition(b"\r\n\r\n")
        if b" 101 " not in status.split(b"\r\n", 1)[0]:
            raise ConnectionError("websocket upgrade refused")
        self.buf = rest

    def close(self) -> None:
        try:
            self.sock.sendall(b"\x88\x80" + os.urandom(4))
        except OSError:
            pass
        self.sock.close()

    def send(self, obj: dict) -> None:
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        n = len(data)
        if n < 126:
            hdr = struct.pack("!BB", 0x81, 0x80 | n)
        elif n < 65536:
            hdr = struct.pack("!BBH", 0x81, 0x80 | 126, n)
        else:
            hdr = struct.pack("!BBQ", 0x81, 0x80 | 127, n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(hdr + mask + masked)

    def _read(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("control socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self) -> dict | None:
        message = b""
        while True:
            b1, b2 = self._read(2)
            fin, op = b1 & 0x80, b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._read(8))[0]
            if n > 64 * 1024 * 1024:
                raise ValueError("frame too large")
            mask = self._read(4) if b2 & 0x80 else None
            payload = self._read(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if op == 0x8:
                return None
            if op == 0x9:  # ping -> pong
                m = os.urandom(4)
                self.sock.sendall(struct.pack("!BB", 0x8A, 0x80 | len(payload)) + m
                                  + bytes(b ^ m[i % 4] for i, b in enumerate(payload)))
                continue
            if op in (0x1, 0x2, 0x0):
                message += payload
                if fin:
                    try:
                        obj = json.loads(message)
                    except ValueError:
                        message = b""
                        continue
                    return obj if isinstance(obj, dict) else None


def _call(ws: _WS, req_id: int, method: str, params: dict, deadline: float) -> dict:
    ws.send({"id": req_id, "method": method, "params": params})
    while time.monotonic() < deadline:
        msg = ws.recv()
        if msg is None:
            raise ConnectionError("closed")
        if msg.get("id") == req_id and "method" not in msg:
            if msg.get("error"):
                raise RuntimeError(str(msg["error"])[:200])
            return msg.get("result") or {}
        if "method" in msg and "id" in msg:
            ws.send({"id": msg["id"], "error": {"code": -32601, "message": "unsupported"}})
    raise TimeoutError("codex daemon query timed out")


def loaded_threads(max_age: float = 5.0, timeout: float = 6.0, max_threads: int = 40) -> dict[str, dict] | None:
    """threadId -> {status, activeFlags, originator, cwd, updatedAt} for threads loaded in the daemon.

    Returns None when the daemon is not reachable (status then stays evidence-limited).
    """
    with _lock:
        if _cache["value"] is not None and time.monotonic() - _cache["at"] < max_age:
            return _cache["value"]
        path = control_socket()
        if path is None:
            return None
        deadline = time.monotonic() + timeout
        out: dict[str, dict] = {}
        try:
            ws = _WS(path, timeout)
        except (OSError, ConnectionError):
            return None
        try:
            from agentdeck import __version__ as ver

            _call(ws, 1, "initialize", {"clientInfo": {"name": "agent-deck-status", "title": "Agent Deck", "version": ver},
                                        "capabilities": {"optOutNotificationMethods": []}}, deadline)
            ws.send({"method": "initialized"})
            ids: list[str] = []
            cursor = None
            for page in range(5):
                params: dict[str, Any] = {"limit": 200}
                if cursor:
                    params["cursor"] = cursor
                res = _call(ws, 2 + page, "thread/loaded/list", params, deadline)
                ids.extend(i for i in res.get("data") or [] if isinstance(i, str))
                cursor = res.get("nextCursor")
                if not cursor:
                    break
            for n, tid in enumerate(ids[:max_threads]):
                res = _call(ws, 100 + n, "thread/read", {"threadId": tid, "includeTurns": False}, deadline)
                th = res.get("thread") or {}
                st = th.get("status") or {}
                out[tid] = {
                    "status": st.get("type") if isinstance(st, dict) else None,
                    "activeFlags": list(st.get("activeFlags") or []) if isinstance(st, dict) else [],
                    "originator": th.get("originator"),
                    "cwd": th.get("cwd"),
                    "updatedAt": th.get("updatedAt"),
                }
        except (OSError, ConnectionError, TimeoutError, RuntimeError, ValueError):
            if not out:
                return None
        finally:
            ws.close()
        _cache.update(at=time.monotonic(), value=out)
        return out
