"""Private, expiring APK download links (tailnet-only via Tailscale Serve)."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .storage import locked, read_json, write_private_json

LINK_TTL_SECONDS = 1800
MAX_APKS = 20


@dataclass
class Apk:
    name: str
    path: Path
    size: int
    modified: str
    sha256: str


def find_apks(directory: Path, with_hash: bool = True) -> list[Apk]:
    """APKs directly inside ``directory`` or one level below, no symlinks escaping it."""
    if not directory.is_dir():
        return []
    root = directory.resolve()
    found: list[Apk] = []
    candidates = sorted(
        [*directory.glob("*.apk"), *directory.glob("*/*.apk")],
        key=lambda p: p.stat().st_mtime if p.exists() else 0,
        reverse=True,
    )
    seen: set[str] = set()
    for p in candidates[:MAX_APKS]:
        real = p.resolve()
        try:
            real.relative_to(root)
        except ValueError:
            continue
        if not real.is_file() or p.name in seen:
            continue
        seen.add(p.name)
        st = real.stat()
        digest = ""
        if with_hash:
            h = hashlib.sha256()
            with open(real, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            digest = h.hexdigest()
        modified = datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        found.append(Apk(p.name, real, st.st_size, modified, digest))
    return found


class DownloadTokens:
    def __init__(self, home: Path, clock: Callable[[], float] = time.time):
        self.path = home / "download-links.json"
        self.clock = clock

    def create(self, ttl: int = LINK_TTL_SECONDS) -> tuple[str, float]:
        token = secrets.token_urlsafe(24)
        now = self.clock()
        expires = now + max(60, min(ttl, 24 * 3600))
        with locked(self.path):
            links = [l for l in read_json(self.path, {}).get("links", []) if l.get("expiresAt", 0) > now]
            links.append({"hash": hashlib.sha256(token.encode()).hexdigest(), "expiresAt": expires})
            write_private_json(self.path, {"links": links[-10:]})
        return token, expires

    def valid(self, token: str) -> bool:
        if not token or len(token) > 100:
            return False
        digest = hashlib.sha256(token.encode()).hexdigest()
        now = self.clock()
        ok = False
        for link in read_json(self.path, {}).get("links", []):
            if link.get("expiresAt", 0) > now and hmac.compare_digest(str(link.get("hash", "")), digest):
                ok = True
        return ok
