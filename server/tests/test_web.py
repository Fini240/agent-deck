"""Web dashboard: shell/assets, APK selection, cookie pairing, CSRF and coexistence
with native Bearer clients."""

import asyncio
import contextlib
import io
import json
import os
import re
import zipfile

import pytest
from fastapi.testclient import TestClient

from agentdeck import config as cfgmod
from agentdeck import web

PROXY_HOST = "deck.example-tailnet.ts.net"
SAME = {"X-AgentDeck-Web": "1", "Origin": "https://localhost"}


# ------------------------------------------------------------------ helpers

def _zip_bytes(extra: dict[str, bytes] | None = None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("AndroidManifest.xml", b"manifest")
        zf.writestr("classes.dex", os.urandom(64))
        for name, data in (extra or {}).items():
            zf.writestr(name, data)
    return buf.getvalue()


def v2_signed_apk() -> bytes:
    """Zip with an APK Signing Block spliced in front of the central directory."""
    data = _zip_bytes()
    eocd = data.rfind(b"PK\x05\x06")
    cd = int.from_bytes(data[eocd + 16 : eocd + 20], "little")
    block = b"\x00" * 24 + web.SIG_BLOCK_MAGIC
    data = data[:cd] + block + data[cd:]
    eocd += len(block)
    return data[: eocd + 16] + (cd + len(block)).to_bytes(4, "little") + data[eocd + 20 :]


def v1_signed_apk() -> bytes:
    return _zip_bytes({"META-INF/MANIFEST.MF": b"m", "META-INF/CERT.SF": b"s", "META-INF/CERT.RSA": b"r"})


def write(path, data: bytes, mtime: float):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    os.utime(path, (mtime, mtime))
    return path


@pytest.fixture
def apk_dir(env):
    d = env["tmp"] / "apks"
    d.mkdir()
    return d


@pytest.fixture
def wh(make_harness, apk_dir):
    h = make_harness(server_config=cfgmod.ServerConfig(apk_dir=str(apk_dir)), extra_hosts=[PROXY_HOST])
    h.https = TestClient(h.app, base_url="https://localhost")
    h.https.__enter__()
    yield h
    h.https.__exit__(None, None, None)


def web_pair(h, client=None, name="Laptop", headers=SAME):
    code, _ = h.app.state.devices.create_pairing_code()
    r = (client or h.https).post("/web/pair", json={"code": code, "deviceName": name}, headers=headers)
    return code, r


def stored_devices(h):
    return h.app.state.devices.list_devices()


# --------------------------------------------------------------- shell/assets

@pytest.mark.parametrize("path", ["/", "/dashboard"])
def test_shell_is_public_with_strict_headers(wh, path):
    r = wh.client.get(path)
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/html")
    csp = r.headers["content-security-policy"]
    assert "script-src 'self'" in csp and "style-src 'self'" in csp and "frame-ancestors 'none'" in csp
    assert "unsafe-inline" not in csp and "unsafe-eval" not in csp
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["x-frame-options"] == "DENY"
    assert r.headers["referrer-policy"] == "no-referrer"
    html = r.text
    # No inline code: every script is external, no <style> blocks, no style/on* attributes.
    assert re.findall(r"<script[^>]*>", html) == ['<script src="/web/static/app.js" defer>']
    assert "<style" not in html and " style=" not in html
    assert not re.search(r"\son[a-z]+=", html)
    assert 'href="/apk"' in html  # APK download offered before pairing


def test_static_assets_allowlist_only(wh):
    for name, kind in (("app.js", "text/javascript"), ("app.css", "text/css"), ("icon.svg", "image/svg+xml")):
        r = wh.client.get(f"/web/static/{name}")
        assert r.status_code == 200 and r.headers["content-type"].startswith(kind)
        assert "unsafe-inline" not in r.headers["content-security-policy"]
    for bad in ("index.html", "web.py", "..%2Fweb.py", "..%2F..%2Fapp.py", "%2e%2e%2fconfig.py", "app.js%00", "APP.JS"):
        assert wh.client.get(f"/web/static/{bad}").status_code == 404, bad
    assert wh.client.get("/web/static/../app.py").status_code == 404
    assert wh.client.get("/web/static/").status_code == 404


def test_frontend_never_renders_html_or_stores_secrets():
    js = (web.STATIC_DIR / "app.js").read_text()
    js = re.sub(r"/\*.*?\*/", "", js, flags=re.S)
    js = re.sub(r"^\s*//.*$", "", js, flags=re.M)
    for forbidden in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "localStorage", "eval(", "new Function"):
        assert forbidden not in js, forbidden
    css = (web.STATIC_DIR / "app.css").read_text()
    assert "@import" not in css and "http" not in css  # no CDN fonts or remote assets
    assert "X-AgentDeck-Web" in js and "credentials: 'same-origin'" in js


def test_web_assets_are_packaged():
    text = (web.STATIC_DIR.parents[1] / "pyproject.toml").read_text()
    assert 'agentdeck = ["web_static/*"]' in text
    for name in ("index.html", *web.ASSETS):
        assert (web.STATIC_DIR / name).is_file()


# ------------------------------------------------------------------------ APK

def test_apk_picks_latest_signed_distributable_and_excludes_unsigned(wh, apk_dir, env):
    t = 1_790_000_000
    write(apk_dir / "android" / "agent-deck-0.1.0-debug.apk", v1_signed_apk(), t)
    newest_good = write(apk_dir / "android" / "agent-deck-0.2.0-debug.apk", v2_signed_apk(), t + 600)
    # Newer, but must never be offered:
    write(apk_dir / "android" / "agent-deck-0.3.0-release-unsigned.apk", _zip_bytes(), t + 1200)
    write(apk_dir / "android" / "agent-deck-0.3.0-RELEASE-UNSIGNED.apk", v2_signed_apk(), t + 1300)  # name says unsigned
    write(apk_dir / "android" / "agent-deck-0.3.0-debug-push-unconfigured.apk", v2_signed_apk(), t + 1400)
    write(apk_dir / "agent-deck-0.4.0.apk", _zip_bytes(), t + 1500)  # no signature at all
    write(apk_dir / "broken-0.5.0.apk", b"not a zip", t + 1600)
    write(apk_dir / "notes-0.9.0.txt", b"source", t + 1700)
    outside = write(env["tmp"] / "outside" / "agent-deck-9.9.9.apk", v2_signed_apk(), t + 1800)
    (apk_dir / "agent-deck-9.9.9.apk").symlink_to(outside)  # symlink escaping the APK dir

    info = wh.client.get("/web/apk-info").json()
    data = newest_good.read_bytes()
    import hashlib

    assert info == {
        "available": True,
        "name": "agent-deck-0.2.0.apk",
        "version": "0.2.0",
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "url": "/apk",
    }
    r = wh.client.get("/apk")
    assert r.status_code == 200 and r.content == data
    assert r.headers["content-type"] == web.APK_MEDIA_TYPE
    assert 'filename="agent-deck-0.2.0.apk"' in r.headers["content-disposition"]
    assert r.headers["x-content-sha256"] == info["sha256"]

    # A newer signed build replaces it, and the cached hash follows the new file.
    newer = write(apk_dir / "android" / "agent-deck-0.2.1-debug.apk", v2_signed_apk(), t + 2000)
    info2 = wh.client.get("/web/apk-info").json()
    assert info2["version"] == "0.2.1" and info2["sha256"] == hashlib.sha256(newer.read_bytes()).hexdigest()


def test_apk_unavailable_when_nothing_signed(wh, apk_dir):
    write(apk_dir / "agent-deck-0.1.0-release-unsigned.apk", _zip_bytes(), 1_790_000_000)
    assert wh.client.get("/web/apk-info").json()["available"] is False
    r = wh.client.get("/apk")
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


def test_apk_routes_take_no_path_input(wh, apk_dir):
    write(apk_dir / "agent-deck-0.1.0-debug.apk", v2_signed_apk(), 1_790_000_000)
    for bad in ("/apk/../app.py", "/apk/agent-deck-0.1.0-debug.apk", "/apk%2F..%2F..%2Fetc%2Fpasswd", "/apk?name=../../x"):
        r = wh.client.get(bad)
        assert r.status_code == 404 or r.content == (apk_dir / "agent-deck-0.1.0-debug.apk").read_bytes(), bad


def test_legacy_expiring_download_routes_still_work(wh, apk_dir):
    write(apk_dir / "agent-deck-0.1.0-debug.apk", v2_signed_apk(), 1_790_000_000)
    token, _ = wh.app.state.downloads.create()
    assert wh.client.get(f"/download/{token}").status_code == 200
    assert wh.client.get(f"/download/{token}/agent-deck-0.1.0-debug.apk").status_code == 200
    assert wh.client.get(f"/download/{token}/..%2F..%2Fdevices.json").status_code == 404
    assert wh.client.get("/download/expired-token").status_code == 404


def test_signature_detection():
    assert web.apk_version("agent-deck-0.1.1-debug.apk") == "0.1.1"
    assert web.apk_version("agent-deck.apk") is None


# -------------------------------------------------------------------- pairing

def test_web_pair_sets_hardened_cookie_and_never_returns_secrets(wh):
    _, r = web_pair(wh)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"deviceId", "deviceName", "serverName"}
    assert body["deviceName"] == "Web: Laptop"
    assert "adk_" not in r.text and "pushKey" not in r.text and "token" not in r.text
    cookie = r.headers["set-cookie"]
    assert cookie.startswith(f"{web.COOKIE_NAME}=adk_")
    lowered = cookie.lower()
    for attr in ("httponly", "secure", "samesite=strict", "path=/", "max-age="):
        assert attr in lowered, attr
    dev = next(d for d in stored_devices(wh) if d["deviceId"] == body["deviceId"])
    assert dev["name"] == "Web: Laptop" and dev["fcmToken"] is None
    # Secure cookie round-trips over HTTPS and authenticates the dashboard.
    me = wh.https.get("/web/me")
    assert me.status_code == 200 and me.json()["deviceId"] == body["deviceId"]
    assert "adk_" not in me.text
    assert wh.https.get("/api/v1/sessions").status_code == 200


def test_secure_cookie_is_not_sent_over_plain_http(wh):
    _, r = web_pair(wh, client=wh.https)
    assert r.status_code == 200
    plain = TestClient(wh.app, base_url="http://localhost")
    plain.cookies = wh.https.cookies  # same jar, but the Secure cookie stays on HTTPS
    assert plain.get("/web/me").status_code == 401


@pytest.mark.parametrize(
    "headers",
    [
        {},  # no header
        {"X-AgentDeck-Web": "0", "Origin": "https://localhost"},
        {"X-AgentDeck-Web": "1", "Origin": "https://evil.example"},
        {"X-AgentDeck-Web": "1", "Origin": "https://localhost.evil.example"},
        {"X-AgentDeck-Web": "1", "Origin": "null"},
        {"X-AgentDeck-Web": "1", "Origin": "https://localhost:8443"},
        {"X-AgentDeck-Web": "1", "Sec-Fetch-Site": "cross-site"},
        {"X-AgentDeck-Web": "1", "Sec-Fetch-Site": "same-site"},
    ],
)
def test_web_pair_rejects_csrf_before_consuming_code(wh, headers):
    code, r = web_pair(wh, headers=headers)
    assert r.status_code == 403 and r.json()["error"]["code"] == "csrf_rejected"
    assert "set-cookie" not in r.headers
    # The one-time code is still valid for the legitimate request.
    r2 = wh.https.post("/web/pair", json={"code": code}, headers=SAME)
    assert r2.status_code == 200
    assert r2.json()["deviceName"] == "Web browser"


def test_web_pair_rejects_bad_codes_and_is_rate_limited(wh):
    r = wh.https.post("/web/pair", json={"code": "WRONGWRONG12"}, headers=SAME)
    assert r.status_code == 401 and r.json()["error"]["code"] == "invalid_pairing_code"
    statuses = [wh.https.post("/web/pair", json={"code": "WRONGWRONG12"}, headers=SAME).status_code for _ in range(12)]
    assert 429 in statuses
    assert stored_devices(wh) == []


def test_reverse_proxy_origin_with_custom_port(wh):
    _, r = web_pair(wh)
    assert r.status_code == 200
    proxied = {"Host": f"{PROXY_HOST}:10443", "X-AgentDeck-Web": "1"}
    wh.terminal.add("m1")
    ok = wh.https.post("/api/v1/sessions/m1/send", json={"text": "hi", "requestId": "req-proxy-1"},
                       headers={**proxied, "Origin": f"https://{PROXY_HOST}:10443"})
    assert ok.status_code == 200, ok.text
    for origin in (f"https://{PROXY_HOST}", f"https://{PROXY_HOST}.evil.example:10443", f"https://{PROXY_HOST}:10444", "https://localhost"):
        bad = wh.https.post("/api/v1/sessions/m1/send", json={"text": "hi", "requestId": "req-proxy-2"},
                            headers={**proxied, "Origin": origin})
        assert bad.status_code == 403, origin
    assert web.origin_matches("https://localhost", ["localhost"])
    assert web.origin_matches("https://localhost:443", ["localhost"])
    assert web.origin_matches("http://localhost:18787", ["localhost:18787"])
    assert not web.origin_matches("http://localhost", ["localhost:18787"])
    assert not web.origin_matches("https://user@localhost", ["localhost"])
    assert [c for c in wh.terminal.calls if c[0] == "send"] == [("send", "m1", "hi", True)]


# ---------------------------------------------------------------- cookie CSRF

def test_cookie_unsafe_methods_require_header_and_same_origin(wh):
    _, r = web_pair(wh)
    device_id = r.json()["deviceId"]
    wh.terminal.add("m1", status="working")
    send = {"text": "follow up", "requestId": "req-web-0001"}
    attempts = [
        ("post", "/api/v1/sessions/m1/send", {"Origin": "https://localhost"}),
        ("post", "/api/v1/sessions/m1/send", {"X-AgentDeck-Web": "1", "Origin": "https://attacker.example"}),
        ("post", "/api/v1/sessions/m1/stop", {}),
        ("post", "/api/v1/sessions", {}),
        ("patch", "/api/v1/settings", {}),
        ("delete", f"/api/v1/devices/{device_id}", {}),
        ("post", "/api/v1/models/refresh", {}),
    ]
    for method, path, headers in attempts:
        resp = wh.https.request(method.upper(), path, json=send, headers=headers)
        assert resp.status_code == 403 and resp.json()["error"]["code"] == "csrf_rejected", (method, path)
    assert wh.terminal.calls == []
    assert any(d["deviceId"] == device_id for d in stored_devices(wh))
    # Cross-origin reads are refused too when the browser declares them.
    assert wh.https.get("/api/v1/sessions", headers={"Origin": "https://attacker.example"}).status_code == 403
    assert wh.https.get("/api/v1/sessions", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
    # Legitimate dashboard request: interrupting follow-up, idempotent per requestId.
    for _ in range(2):
        ok = wh.https.post("/api/v1/sessions/m1/send", json=send, headers=SAME)
        assert ok.status_code == 200 and ok.json() == {"accepted": True, "sessionId": "m1"}
    assert wh.terminal.calls == [("send", "m1", "follow up", True)]


def test_cookie_and_native_bearer_coexist(wh):
    native = wh.pair(name="Fold")
    _, r = web_pair(wh)
    web_id = r.json()["deviceId"]
    wh.terminal.add("m1")
    # Native client: Bearer, no web header, unchanged.
    ok = wh.client.post("/api/v1/sessions/m1/send", json={"text": "native", "requestId": "req-nat-001"}, headers=wh.auth(native))
    assert ok.status_code == 200
    # A Bearer header never falls back to the cookie, even if the bearer is bad.
    bad = wh.https.get("/api/v1/sessions", headers={"Authorization": "Bearer adk_wrong"})
    assert bad.status_code == 401
    bad2 = wh.https.get("/api/v1/sessions", headers={"Authorization": "Basic abc"})
    assert bad2.status_code == 401
    # A valid bearer from the browser's jar context is still the native device.
    me = wh.https.get("/web/me", headers=wh.auth(native))
    assert me.json()["deviceId"] == native["deviceId"]
    me_cookie = wh.https.get("/web/me")
    assert me_cookie.json()["deviceId"] == web_id
    # Each device can only remove itself.
    other = wh.https.delete(f"/api/v1/devices/{native['deviceId']}", headers=SAME)
    assert other.status_code == 403
    # Local /notify never accepts the device cookie.
    notify = wh.https.post("/api/v1/notify", json={"sessionId": "m1", "kind": "progress", "title": "x"}, headers=SAME)
    assert notify.status_code == 401


def test_logout_revokes_only_this_browser_and_clears_cookie(wh):
    native = wh.pair(name="Fold")
    _, r = web_pair(wh)
    web_id = r.json()["deviceId"]
    raw_cookie = wh.https.cookies.get(web.COOKIE_NAME)
    # Logout is CSRF-protected too.
    assert wh.https.post("/web/logout", headers={"Origin": "https://localhost"}).status_code == 403
    assert wh.https.post("/web/logout", headers={"X-AgentDeck-Web": "1", "Origin": "https://evil.example"}).status_code == 403
    assert any(d["deviceId"] == web_id for d in stored_devices(wh))
    out = wh.https.post("/web/logout", headers=SAME)
    assert out.status_code == 200 and out.json() == {"accepted": True, "revoked": True}
    set_cookie = out.headers["set-cookie"].lower()
    assert set_cookie.startswith(f"{web.COOKIE_NAME}=") and ("max-age=0" in set_cookie or "expires=" in set_cookie)
    ids = {d["deviceId"] for d in stored_devices(wh)}
    assert web_id not in ids and native["deviceId"] in ids
    # Replaying the old cookie value is refused without leaking any session data.
    wh.providers.sessions = [{"id": "claude:n1", "agent": "claude", "nativeId": "n1", "title": "secret project",
                              "status": "idle", "updatedAt": "2026-10-07T00:00:00.000Z"}]
    replay = TestClient(wh.app, base_url="https://localhost")
    replay.cookies.set(web.COOKIE_NAME, raw_cookie, domain="localhost")
    for path in ("/api/v1/sessions", "/web/me", "/api/v1/sessions/claude:n1/messages", "/api/v1/status"):
        resp = replay.get(path)
        assert resp.status_code == 401, path
        assert set(resp.json()) == {"error"} and "secret project" not in resp.text
    # Logging out again (no valid cookie) is harmless.
    again = wh.https.post("/web/logout", headers=SAME)
    assert again.status_code == 200 and again.json()["revoked"] is False


def test_missing_or_admin_revoked_cookie_is_401(wh):
    assert wh.https.get("/api/v1/sessions").status_code == 401
    assert wh.https.get("/web/me").status_code == 401
    _, r = web_pair(wh)
    assert wh.https.get("/api/v1/sessions").status_code == 200
    wh.app.state.devices.revoke(r.json()["deviceId"])  # e.g. `agentdeck devices revoke`
    resp = wh.https.get("/api/v1/sessions")
    assert resp.status_code == 401 and set(resp.json()) == {"error"}
    garbage = TestClient(wh.app, base_url="https://localhost")
    garbage.cookies.set(web.COOKIE_NAME, "x" * 600, domain="localhost")
    assert garbage.get("/api/v1/sessions").status_code == 401


def test_token_never_written_to_audit_or_device_store(wh, env):
    _, r = web_pair(wh)
    raw = wh.https.cookies.get(web.COOKIE_NAME)
    assert raw and raw.startswith("adk_")
    wh.terminal.add("m1")
    wh.https.post("/api/v1/sessions/m1/send", json={"text": "x", "requestId": "req-audit-01"}, headers=SAME)
    wh.https.post("/web/logout", headers=SAME)
    for path in env["home"].rglob("*"):
        if path.is_file():
            assert raw not in path.read_text(errors="ignore"), path


# ------------------------------------------------------------------------ SSE

class _CookieStream:
    def __init__(self, app, cookie):
        self.app, self.cookie = app, cookie
        self.chunks: list[str] = []
        self.status = None
        self._gone = asyncio.Event()
        self.task = None

    async def open(self):
        sent = False

        async def receive():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": b"", "more_body": False}
            await self._gone.wait()
            return {"type": "http.disconnect"}

        async def send(msg):
            if msg["type"] == "http.response.start":
                self.status = msg["status"]
            elif msg.get("body"):
                self.chunks.append(msg["body"].decode())

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "GET",
            "scheme": "https", "path": "/api/v1/events", "raw_path": b"/api/v1/events", "query_string": b"",
            "root_path": "", "client": ("127.0.0.1", 5000), "server": ("localhost", 443),
            "headers": [(b"host", b"localhost"), (b"cookie", f"{web.COOKIE_NAME}={self.cookie}".encode())],
        }
        self.task = asyncio.create_task(self.app(scope, receive, send))
        for _ in range(200):
            if self.task.done() or any("retry" in c for c in self.chunks):
                break
            await asyncio.sleep(0.01)
        return self

    async def close(self):
        self._gone.set()
        if self.task and not self.task.done():
            self.task.cancel()
            with contextlib.suppress(BaseException):
                await self.task


def test_cookie_event_stream_ends_when_browser_device_is_revoked(wh):
    _, r = web_pair(wh)
    cookie = wh.https.cookies.get(web.COOKIE_NAME)

    async def run():
        s = await _CookieStream(wh.app, cookie).open()
        assert s.status == 200
        wh.app.state.devices.revoke(r.json()["deviceId"])
        wh.app.state.bus.publish("notification", "m1", {"title": "t", "body": "private details"})
        await asyncio.wait_for(asyncio.shield(s.task), 3)
        assert "private details" not in "".join(s.chunks)
        await s.close()

    asyncio.run(run())


def test_shell_and_apk_info_need_no_auth_but_api_does(wh):
    assert wh.client.get("/").status_code == 200
    assert wh.client.get("/web/apk-info").status_code == 200
    assert wh.client.get("/api/v1/sessions").status_code == 401
    assert json.loads(wh.client.get("/web/apk-info").text)["url"] == "/apk"
