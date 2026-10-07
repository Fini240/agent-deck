"""Encrypted FCM push.

Wire format (FCM ``data`` map, all strings)::

    {"v": "1", "nonce": b64(12 bytes), "ciphertext": b64(AES-256-GCM ct || 16-byte tag)}

No AAD. The plaintext JSON is never placed in FCM ``notification`` fields, so
Google only ever sees ciphertext. Credentials come from a Firebase service
account JSON configured locally; when it is missing the sender reports a clear
``unavailable`` state instead of pretending notifications work.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .storage import is_private_file

log = logging.getLogger("agentdeck.push")

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
ALERT_KINDS = {"completed", "error", "input"}


def _fit_payload(payload: dict[str, Any]) -> bytes:
    # Base64 and the GCM tag expand the plaintext. Leave room for all three
    # FCM data keys/values below its 4096-byte limit, including non-ASCII text.
    budget = 2800
    fitted = dict(payload)
    encode = lambda: json.dumps(fitted, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()
    for field in ("body", "stage", "title"):
        if len(encode()) <= budget:
            break
        text = fitted.get(field)
        if not isinstance(text, str):
            continue
        lo, hi = 0, len(text)
        while lo < hi:
            mid = (lo + hi + 1) // 2
            fitted[field] = text[:mid] + "…"
            if len(encode()) <= budget:
                lo = mid
            else:
                hi = mid - 1
        fitted[field] = text[:lo] + ("…" if lo else "")
    data = encode()
    if len(data) > budget:
        raise ValueError("push metadata exceeds the encrypted payload budget")
    return data


def encrypt_payload(push_key_b64: str, payload: dict[str, Any], nonce: bytes | None = None) -> dict[str, str]:
    key = base64.b64decode(push_key_b64)
    if len(key) != 32:
        raise ValueError("push key must be 32 bytes")
    nonce = nonce if nonce is not None else os.urandom(12)
    if len(nonce) != 12:
        raise ValueError("nonce must be 12 bytes")
    plaintext = _fit_payload(payload)
    ct = AESGCM(key).encrypt(nonce, plaintext, None)
    return {
        "v": "1",
        "nonce": base64.b64encode(nonce).decode(),
        "ciphertext": base64.b64encode(ct).decode(),
    }


def decrypt_payload(push_key_b64: str, data: dict[str, str]) -> dict[str, Any]:
    if data.get("v") != "1":
        raise ValueError("unsupported payload version")
    key = base64.b64decode(push_key_b64)
    nonce = base64.b64decode(data["nonce"])
    ct = base64.b64decode(data["ciphertext"])
    return json.loads(AESGCM(key).decrypt(nonce, ct, None))


def build_fcm_message(fcm_token: str, encrypted: dict[str, str], kind: str, session_id: str | None) -> dict[str, Any]:
    alert = kind in ALERT_KINDS
    android: dict[str, Any] = {
        # High priority so background updates arrive promptly; the app always
        # shows a notification for these, which keeps FCM from deprioritising.
        "priority": "HIGH",
        "ttl": "3600s" if alert else "300s",
    }
    if not alert and session_id:
        # Collapse superseded progress for the same session while the phone is offline.
        android["collapse_key"] = ("p_" + session_id)[:64]
    return {"message": {"token": fcm_token, "data": encrypted, "android": android}}


@dataclass
class PushStatus:
    available: bool
    reason: str | None
    project_id: str | None


class PushSender(Protocol):
    def status(self) -> PushStatus: ...

    def send(self, fcm_token: str, message: dict[str, Any]) -> str:
        """Return 'ok', 'unregistered' (drop token) or 'error'."""


class FcmSender:
    """FCM HTTP v1 sender using a service-account JSON (google-auth)."""

    def __init__(self, service_account_file: Path, project_id: str | None = None, enabled: bool = True):
        self.path = service_account_file
        self.project_id_override = project_id
        self.enabled = enabled
        self._creds = None
        self._session = None
        self._project_id: str | None = None
        self._error: str | None = None
        self._lock = threading.Lock()
        self._loaded_mtime: float | None = None

    def _load(self) -> None:
        if not self.enabled:
            self._error = "push disabled in config.json (fcm.enabled=false)"
            return
        try:
            st = self.path.stat()
        except FileNotFoundError:
            self._error = f"Firebase service account not configured ({self.path.name} missing in agent-deck home)"
            self._creds = None
            return
        if self._loaded_mtime == st.st_mtime and (self._creds is not None or self._error):
            return
        self._loaded_mtime = st.st_mtime
        self._creds = None
        if not is_private_file(self.path):
            self._error = "Firebase service account file must be mode 0600"
            return
        try:
            from google.auth.transport.requests import AuthorizedSession
            from google.oauth2 import service_account

            with open(self.path) as fh:
                info = json.load(fh)
            creds = service_account.Credentials.from_service_account_info(info, scopes=[FCM_SCOPE])
            self._project_id = self.project_id_override or info.get("project_id")
            if not self._project_id:
                self._error = "Firebase project id unknown (set fcm.projectId)"
                return
            self._creds = creds
            self._session = AuthorizedSession(creds)
            self._error = None
        except Exception as exc:  # never include key material in the message
            self._error = f"Firebase credentials could not be loaded ({type(exc).__name__})"

    def status(self) -> PushStatus:
        with self._lock:
            self._load()
            return PushStatus(self._creds is not None, self._error, self._project_id)

    def send(self, fcm_token: str, message: dict[str, Any]) -> str:
        with self._lock:
            self._load()
            session, project = self._session, self._project_id
            if self._creds is None or session is None:
                return "unavailable"
        url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"
        try:
            resp = session.post(url, json=message, timeout=15)
        except Exception as exc:
            log.warning("FCM send failed: %s", type(exc).__name__)
            return "error"
        if resp.status_code == 200:
            return "ok"
        try:
            details = resp.json().get("error", {})
        except ValueError:
            details = {}
        codes = {d.get("errorCode") for d in details.get("details", []) if isinstance(d, dict)}
        if resp.status_code == 404 or "UNREGISTERED" in codes:
            return "unregistered"
        log.warning("FCM send rejected: HTTP %s %s", resp.status_code, details.get("status"))
        return "error"


class PushService:
    """Fan-out of encrypted events to all paired devices with an FCM token."""

    def __init__(self, devices, sender: PushSender, clock=time.time):
        self.devices = devices
        self.sender = sender
        self.clock = clock
        self.stats = {"sent": 0, "failed": 0, "lastError": None, "lastSentAt": None}

    def status(self) -> dict[str, Any]:
        st = self.sender.status()
        with_tokens = sum(1 for d in self.devices.list_devices() if d.get("fcmToken"))
        return {
            "available": st.available,
            "reason": st.reason,
            "projectId": st.project_id,
            "devicesWithToken": with_tokens,
            **self.stats,
        }

    def deliver(self, payload: dict[str, Any], only_device: str | None = None) -> dict[str, int]:
        """Blocking; call from a worker thread."""
        result = {"ok": 0, "error": 0, "unavailable": 0, "unregistered": 0, "skipped": 0}
        for dev in self.devices.list_devices():
            if only_device and dev["deviceId"] != only_device:
                continue
            token = dev.get("fcmToken")
            if not token:
                result["skipped"] += 1
                continue
            encrypted = encrypt_payload(dev["pushKey"], payload)
            msg = build_fcm_message(token, encrypted, payload.get("type", "progress"), payload.get("sessionId"))
            outcome = self.sender.send(token, msg)
            result[outcome if outcome in result else "error"] += 1
            if outcome == "unregistered":
                self.devices.clear_fcm_token_if(dev["deviceId"], token)
            if outcome == "ok":
                self.stats["sent"] += 1
                self.stats["lastSentAt"] = self.clock()
            elif outcome != "unavailable":
                self.stats["failed"] += 1
                self.stats["lastError"] = outcome
        return result
