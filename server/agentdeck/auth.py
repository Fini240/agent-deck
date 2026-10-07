"""Device pairing and bearer-token authentication.

* Pairing codes: short-lived, single-use, stored only as SHA-256 hashes.
* Device tokens: 256-bit random, stored only as SHA-256 hashes.
* Push keys: raw 32-byte AES keys per device (needed for encryption, file is 0600).
* Local helper token: ``~/.agent-deck/local-token`` for ``/notify`` from local CLIs.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote

from .errors import AuthError, ForbiddenError, InvalidInputError, RateLimitedError
from .storage import locked, read_json, write_private_json

PAIRING_TTL_SECONDS = 300
MAX_PENDING_CODES = 5
MAX_DEVICES = 20
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I
CODE_LENGTH = 12  # ~60 bits
FCM_TOKEN_RE = re.compile(r"^[A-Za-z0-9_:\-.]{20,4096}$")


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def utc_iso(ts: float | None = None) -> str:
    dt = datetime.fromtimestamp(ts if ts is not None else time.time(), tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def normalize_code(code: str) -> str:
    return re.sub(r"[\s-]", "", code or "").upper()


class RateLimiter:
    """Sliding-window limiter keyed by an arbitrary string."""

    def __init__(self, limit: int, window_seconds: float, clock: Callable[[], float] = time.monotonic):
        self.limit = limit
        self.window = window_seconds
        self.clock = clock
        self._hits: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def check(self, key: str = "global") -> None:
        now = self.clock()
        with self._lock:
            hits = self._hits.setdefault(key, deque())
            while hits and now - hits[0] > self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                raise RateLimitedError("too many attempts, try again later")
            hits.append(now)
            if len(self._hits) > 1000:  # bounded memory
                for k in [k for k, v in self._hits.items() if not v][:500]:
                    self._hits.pop(k, None)


class DeviceStore:
    def __init__(self, home: Path, clock: Callable[[], float] = time.time):
        self.devices_path = home / "devices.json"
        self.pairing_path = home / "pairing.json"
        self.clock = clock

    # ------------------------------------------------------------- pairing
    def create_pairing_code(self, ttl: int = PAIRING_TTL_SECONDS) -> tuple[str, float]:
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
        now = self.clock()
        expires = now + max(30, min(ttl, 3600))
        with locked(self.pairing_path):
            pending = self._live_codes(read_json(self.pairing_path, {}).get("codes", []), now)
            pending.append({"hash": _sha256(code), "expiresAt": expires})
            pending = pending[-MAX_PENDING_CODES:]
            write_private_json(self.pairing_path, {"codes": pending})
        return code, expires

    def _live_codes(self, codes: list, now: float) -> list[dict]:
        return [c for c in codes if isinstance(c, dict) and c.get("expiresAt", 0) > now]

    def consume_pairing_code(self, code: str) -> bool:
        digest = _sha256(normalize_code(code))
        now = self.clock()
        with locked(self.pairing_path):
            codes = self._live_codes(read_json(self.pairing_path, {}).get("codes", []), now)
            match = None
            for c in codes:
                if hmac.compare_digest(c.get("hash", ""), digest):
                    match = c
            if match is not None:
                codes.remove(match)
            write_private_json(self.pairing_path, {"codes": codes})
        return match is not None

    def pair(self, code: str, device_name: str, fcm_token: str | None) -> dict[str, str]:
        if not isinstance(code, str) or not 6 <= len(code) <= 64:
            raise AuthError("invalid or expired pairing code", code="invalid_pairing_code")
        name = (device_name or "Android").strip()[:80] if isinstance(device_name, str) else "Android"
        if fcm_token is not None and (not isinstance(fcm_token, str) or not FCM_TOKEN_RE.match(fcm_token)):
            raise InvalidInputError("invalid fcmToken")
        if not self.consume_pairing_code(code):
            raise AuthError("invalid or expired pairing code", code="invalid_pairing_code")
        device_id = "dev_" + uuid.uuid4().hex[:16]
        token = "adk_" + secrets.token_urlsafe(32)
        push_key = secrets.token_bytes(32)
        with locked(self.devices_path):
            data = self._load()
            if len(data["devices"]) >= MAX_DEVICES:
                raise ForbiddenError("device limit reached; revoke an old device first")
            data["devices"].append(
                {
                    "deviceId": device_id,
                    "name": name or "Android",
                    "tokenHash": _sha256(token),
                    "pushKey": base64.b64encode(push_key).decode(),
                    "fcmToken": fcm_token,
                    "createdAt": utc_iso(self.clock()),
                    "lastSeenAt": None,
                }
            )
            write_private_json(self.devices_path, data)
        return {"deviceId": device_id, "token": token, "pushKey": base64.b64encode(push_key).decode()}

    # -------------------------------------------------------------- devices
    def _load(self) -> dict[str, Any]:
        data = read_json(self.devices_path, {})
        if not isinstance(data, dict) or not isinstance(data.get("devices"), list):
            data = {"devices": []}
        return data

    def list_devices(self) -> list[dict[str, Any]]:
        return list(self._load()["devices"])

    def authenticate(self, token: str) -> dict[str, Any]:
        if not token or len(token) > 512:
            raise AuthError("missing or invalid token")
        digest = _sha256(token)
        found = None
        for dev in self._load()["devices"]:
            if hmac.compare_digest(str(dev.get("tokenHash", "")), digest):
                found = dev
        if found is None:
            raise AuthError("missing or invalid token")
        return found

    def touch(self, device_id: str, min_interval: float = 300) -> None:
        now = self.clock()
        with locked(self.devices_path):
            data = self._load()
            for dev in data["devices"]:
                if dev["deviceId"] == device_id:
                    last = dev.get("_lastSeenTs", 0)
                    if now - last < min_interval:
                        return
                    dev["_lastSeenTs"] = now
                    dev["lastSeenAt"] = utc_iso(now)
                    write_private_json(self.devices_path, data)
                    return

    def set_fcm_token(self, device_id: str, fcm_token: str | None) -> None:
        if fcm_token is not None and (not isinstance(fcm_token, str) or not FCM_TOKEN_RE.match(fcm_token)):
            raise InvalidInputError("invalid fcmToken")
        with locked(self.devices_path):
            data = self._load()
            for dev in data["devices"]:
                if dev["deviceId"] == device_id:
                    dev["fcmToken"] = fcm_token
                    write_private_json(self.devices_path, data)
                    return
        raise AuthError("device revoked")

    def clear_fcm_token_if(self, device_id: str, fcm_token: str) -> None:
        with locked(self.devices_path):
            data = self._load()
            for dev in data["devices"]:
                if dev["deviceId"] == device_id and dev.get("fcmToken") == fcm_token:
                    dev["fcmToken"] = None
                    write_private_json(self.devices_path, data)
                    return

    def revoke(self, device_id: str) -> bool:
        with locked(self.devices_path):
            data = self._load()
            before = len(data["devices"])
            data["devices"] = [d for d in data["devices"] if d["deviceId"] != device_id]
            write_private_json(self.devices_path, data)
            return len(data["devices"]) != before


def pairing_uri(base_url: str, code: str) -> str:
    return f"agentdeck://pair?server={quote(base_url, safe='')}&code={quote(code, safe='')}"


def check_local_token(provided: str, expected: str) -> None:
    if not provided or not expected or not hmac.compare_digest(provided.encode(), expected.encode()):
        raise AuthError("local helper token required")
