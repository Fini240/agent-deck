"""Browser dashboard: static shell, cookie pairing, CSRF checks and APK download.

The dashboard reuses the native ``/api/v1`` endpoints. A paired browser holds its
device token only in an HttpOnly, Secure, SameSite=Strict cookie; JavaScript never
sees the token or push key. Cookie-authenticated requests must carry
``X-AgentDeck-Web: 1`` on every unsafe method and a same-origin ``Origin`` when one
is sent. A custom header cannot be added cross-origin without a CORS preflight,
and this server never answers preflights, so the header alone blocks CSRF; the
Origin and Fetch-Metadata checks are defense in depth.
"""

from __future__ import annotations

import hashlib
import re
import threading
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from . import __version__
from .auth import DeviceStore, RateLimiter
from .downloads import Apk, find_apks
from .errors import AuthError, ForbiddenError, NotFoundError

COOKIE_NAME = "agentdeck_web"
COOKIE_MAX_AGE = 180 * 24 * 3600
WEB_HEADER = "x-agentdeck-web"
SAFE_METHODS = ("GET", "HEAD", "OPTIONS")
STATIC_DIR = Path(__file__).resolve().parent / "web_static"
# Fixed allowlist: the URL never selects a filesystem path.
ASSETS = {
    "app.js": "text/javascript; charset=utf-8",
    "app.css": "text/css; charset=utf-8",
    "icon.svg": "image/svg+xml",
}
CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
    "connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'self'; "
    "frame-ancestors 'none'"
)
APK_MEDIA_TYPE = "application/vnd.android.package-archive"
# Builds that must never be offered: unsigned release output and the push-less test variant.
EXCLUDED_APK_MARKERS = ("unsigned", "unconfigured")
VERSION_RE = re.compile(r"(\d+\.\d+(?:\.\d+)?)")
SIG_BLOCK_MAGIC = b"APK Sig Block 42"


# --------------------------------------------------------------------- CSRF

def _default_port(scheme: str) -> int:
    return 443 if scheme == "https" else 80


def _split_host(value: str, scheme: str) -> tuple[str, int] | None:
    value = (value or "").strip().lower()
    if not value or any(c in value for c in "/@?# "):
        return None
    try:
        parts = urlsplit(f"{scheme}://{value}")
        host, port = parts.hostname, parts.port
    except ValueError:
        return None
    if not host:
        return None
    return host, port or _default_port(scheme)


def origin_matches(origin: str, hosts: list[str]) -> bool:
    """True if ``origin`` names the same host and port as one of the request hosts.

    Behind Tailscale Serve the browser talks HTTPS to the proxy while the helper
    sees plain HTTP, so the comparison uses the Origin's own scheme for default
    ports instead of the scheme of the proxied connection.
    """
    try:
        parts = urlsplit(origin.strip())
        scheme, host, port = parts.scheme.lower(), parts.hostname, parts.port
    except ValueError:
        return False
    if scheme not in ("http", "https") or not host or parts.path not in ("", "/") or parts.username:
        return False
    want = (host.lower(), port or _default_port(scheme))
    return any(_split_host(h, scheme) == want for h in hosts if h)


def check_browser_request(request: Request, *, require_header: bool) -> None:
    """Reject cross-site browser requests before any cookie or pairing code is used."""
    if require_header and request.headers.get(WEB_HEADER) != "1":
        raise ForbiddenError("missing X-AgentDeck-Web header", code="csrf_rejected")
    site = request.headers.get("sec-fetch-site")
    if site is not None and site.lower() not in ("same-origin", "none"):
        raise ForbiddenError("cross-site request rejected", code="csrf_rejected")
    origin = request.headers.get("origin")
    if origin is not None:
        hosts = [request.headers.get("host") or "", request.headers.get("x-forwarded-host") or ""]
        if not origin_matches(origin, hosts):
            raise ForbiddenError("cross-origin request rejected", code="csrf_rejected")


def device_credentials(request: Request) -> tuple[str, bool]:
    """Return ``(token, via_cookie)``. A native Authorization header always wins and
    never falls back to the cookie; the cookie is only consulted without one."""
    header = request.headers.get("authorization")
    if header is not None:
        scheme, _, token = header.partition(" ")
        return (token.strip() if scheme.lower() == "bearer" else ""), False
    cookie = request.cookies.get(COOKIE_NAME) or ""
    if not cookie:
        return "", False
    check_browser_request(request, require_header=request.method.upper() not in SAFE_METHODS)
    return cookie, True


# ---------------------------------------------------------------------- APK

def apk_is_signed(path: Path) -> bool:
    """APK Signature Scheme v2+ block before the central directory, or v1 JAR signature."""
    try:
        with open(path, "rb") as fh:
            fh.seek(0, 2)
            size = fh.tell()
            tail_len = min(size, 65_557)
            fh.seek(size - tail_len)
            tail = fh.read(tail_len)
            eocd = tail.rfind(b"PK\x05\x06")
            if eocd < 0 or eocd + 20 > len(tail):
                return False
            cd_offset = int.from_bytes(tail[eocd + 16 : eocd + 20], "little")
            if 16 <= cd_offset <= size:
                fh.seek(cd_offset - 16)
                if fh.read(16) == SIG_BLOCK_MAGIC:
                    return True
        with zipfile.ZipFile(path) as zf:
            return any(
                n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))
                for n in zf.namelist()
            )
    except (OSError, zipfile.BadZipFile, ValueError):
        return False


def apk_version(name: str) -> str | None:
    m = VERSION_RE.search(name)
    return m.group(1) if m else None


def _distributable(apk: Apk) -> bool:
    lower = apk.name.lower()
    return lower.endswith(".apk") and not any(marker in lower for marker in EXCLUDED_APK_MARKERS)


class ApkCatalog:
    """Picks the newest signed, distributable APK from the configured directory."""

    def __init__(self, directory: Callable[[], Path]):
        self.directory = directory
        self._hashes: dict[tuple[str, int, int], str] = {}
        self._signed: dict[tuple[str, int, int], bool] = {}
        self._lock = threading.Lock()

    @staticmethod
    def _key(apk: Apk) -> tuple[str, int, int]:
        st = apk.path.stat()
        return str(apk.path), st.st_size, st.st_mtime_ns

    def latest(self) -> tuple[Apk, str] | None:
        candidates = []
        for apk in find_apks(self.directory(), with_hash=False):
            if not _distributable(apk):
                continue
            try:
                key = self._key(apk)
            except OSError:
                continue
            with self._lock:
                signed = self._signed.get(key)
            if signed is None:
                signed = apk_is_signed(apk.path)
                with self._lock:
                    self._signed[key] = signed
            if signed:
                # Within the same minute prefer a versioned name (copies of one build).
                mtime = key[2] / 1e9
                candidates.append(((int(mtime // 60), apk_version(apk.name) is not None, mtime), apk, key))
        if not candidates:
            return None
        _, apk, key = max(candidates, key=lambda c: c[0])
        with self._lock:
            digest = self._hashes.get(key)
        if digest is None:
            h = hashlib.sha256()
            with open(apk.path, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            digest = h.hexdigest()
            with self._lock:
                if len(self._hashes) > 64:
                    self._hashes.clear()
                self._hashes[key] = digest
        return apk, digest

    def info(self) -> dict[str, Any]:
        found = self.latest()
        if found is None:
            return {"available": False, "name": None, "version": None, "size": None, "sha256": None, "url": "/apk"}
        apk, digest = found
        return {
            "available": True,
            "name": download_name(apk),
            "version": apk_version(apk.name),
            "size": apk.size,
            "sha256": digest,
            "url": "/apk",
        }


def download_name(apk: Apk) -> str:
    version = apk_version(apk.name)
    return f"agent-deck-{version}.apk" if version else "agent-deck.apk"


# ------------------------------------------------------------------- routes

@dataclass
class WebDeps:
    devices: DeviceStore
    apk_dir: Callable[[], Path]
    read_json_body: Callable[[Request], Any]
    authenticate: Callable[[Request], Any]  # async: returns the device or raises AuthError
    on_revoke: Callable[[str], None]
    audit: Callable[..., None]
    server_name: Callable[[], str]


def _security_headers(response: Response) -> Response:
    response.headers["Content-Security-Policy"] = CSP
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
    response.headers["Cross-Origin-Resource-Policy"] = "same-origin"
    return response


def _set_cookie(response: Response, token: str) -> None:
    response.set_cookie(
        COOKIE_NAME, token, max_age=COOKIE_MAX_AGE, path="/", secure=True, httponly=True, samesite="strict"
    )


def _clear_cookie(response: Response) -> None:
    response.delete_cookie(COOKIE_NAME, path="/", secure=True, httponly=True, samesite="strict")


def _web_device_name(raw: Any) -> str:
    label = " ".join(raw.split())[:60] if isinstance(raw, str) else ""
    return f"Web: {label}" if label else "Web browser"


def register(app: FastAPI, deps: WebDeps) -> None:
    catalog = ApkCatalog(deps.apk_dir)
    pair_limiter = RateLimiter(10, 600)
    apk_limiter = RateLimiter(60, 600)
    index_html = (STATIC_DIR / "index.html").read_bytes()
    app.state.apk_catalog = catalog

    def shell() -> Response:
        return _security_headers(Response(index_html, media_type="text/html; charset=utf-8"))

    @app.get("/")
    async def root():
        return shell()

    @app.get("/dashboard")
    async def dashboard():
        return shell()

    @app.get("/web/static/{name}")
    async def static_asset(name: str):
        media = ASSETS.get(name)
        if media is None:
            raise NotFoundError("not found")
        data = await run_in_threadpool((STATIC_DIR / name).read_bytes)
        return _security_headers(Response(data, media_type=media))

    @app.get("/web/apk-info")
    async def apk_info():
        return _security_headers(JSONResponse(await run_in_threadpool(catalog.info)))

    @app.get("/apk")
    async def apk_download():
        apk_limiter.check()
        found = await run_in_threadpool(catalog.latest)
        if found is None:
            raise NotFoundError("no signed APK has been built yet")
        apk, digest = found
        response = FileResponse(apk.path, media_type=APK_MEDIA_TYPE, filename=download_name(apk))
        response.headers["X-Content-SHA256"] = digest
        return _security_headers(response)

    @app.post("/web/pair")
    async def web_pair(request: Request):
        # Origin and header are checked before the rate limiter and the one-time code.
        check_browser_request(request, require_header=True)
        pair_limiter.check()
        body = await deps.read_json_body(request)
        name = _web_device_name(body.get("deviceName"))
        result = await run_in_threadpool(deps.devices.pair, body.get("code"), name, None)
        deps.audit({"deviceId": result["deviceId"]}, "pair", name=name, web=True)
        response = JSONResponse({"deviceId": result["deviceId"], "deviceName": name, "serverName": deps.server_name()})
        _set_cookie(response, result["token"])
        return _security_headers(response)

    @app.post("/web/logout")
    async def web_logout(request: Request):
        check_browser_request(request, require_header=True)
        token = request.cookies.get(COOKIE_NAME) or ""
        revoked = False
        if token:
            try:
                device = await run_in_threadpool(deps.devices.authenticate, token)
            except AuthError:
                device = None
            if device is not None:
                revoked = await run_in_threadpool(deps.devices.revoke, device["deviceId"])
                deps.on_revoke(device["deviceId"])
                deps.audit(device, "unpair", web=True)
        response = JSONResponse({"accepted": True, "revoked": revoked})
        _clear_cookie(response)
        return _security_headers(response)

    @app.get("/web/me")
    async def web_me(request: Request):
        device = await deps.authenticate(request)
        return _security_headers(
            JSONResponse(
                {
                    "deviceId": device["deviceId"],
                    "deviceName": device.get("name"),
                    "serverName": deps.server_name(),
                    "version": __version__,
                }
            )
        )
