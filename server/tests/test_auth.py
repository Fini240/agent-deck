import json
import os
import stat

import pytest

from agentdeck import config as cfgmod
from agentdeck.auth import DeviceStore, RateLimiter
from agentdeck.errors import RateLimitedError


def mode(p):
    return stat.S_IMODE(os.stat(p).st_mode)


def test_health_is_public_and_minimal(harness):
    r = harness.client.get("/health")
    assert r.status_code == 200
    assert set(r.json()) == {"status", "version"}


@pytest.mark.parametrize("path", ["/api/v1/sessions", "/api/v1/settings", "/api/v1/models", "/api/v1/status", "/api/v1/events"])
def test_every_api_route_requires_device_token(harness, path):
    assert harness.client.get(path).status_code == 401
    r = harness.client.get(path, headers={"Authorization": "Bearer adk_wrong"})
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "unauthorized"


def test_pairing_is_single_use_and_returns_credentials(harness):
    code, _ = harness.app.state.devices.create_pairing_code()
    r = harness.client.post("/api/v1/pair", json={"code": code, "deviceName": "Fold 7"})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"deviceId", "token", "pushKey", "serverName"}
    import base64

    assert len(base64.b64decode(body["pushKey"])) == 32
    assert harness.client.get("/api/v1/sessions", headers=harness.auth(body)).status_code == 200
    again = harness.client.post("/api/v1/pair", json={"code": code, "deviceName": "attacker"})
    assert again.status_code == 401
    assert again.json()["error"]["code"] == "invalid_pairing_code"


def test_pairing_code_formatting_is_tolerated(harness):
    code, _ = harness.app.state.devices.create_pairing_code()
    dashed = f"{code[:4].lower()}-{code[4:8]}-{code[8:]}"
    assert harness.client.post("/api/v1/pair", json={"code": dashed, "deviceName": "x"}).status_code == 200


def test_pairing_code_expires(harness):
    code, _ = harness.app.state.devices.create_pairing_code(ttl=60)
    harness.clock.advance(61)
    assert harness.client.post("/api/v1/pair", json={"code": code, "deviceName": "x"}).status_code == 401


def test_pairing_rate_limited(harness):
    for _ in range(10):
        harness.client.post("/api/v1/pair", json={"code": "WRONGCODE123", "deviceName": "x"})
    code, _ = harness.app.state.devices.create_pairing_code()
    r = harness.client.post("/api/v1/pair", json={"code": code, "deviceName": "x"})
    assert r.status_code == 429
    assert r.json()["error"]["code"] == "rate_limited"


def test_rate_limiter_window():
    t = [0.0]
    rl = RateLimiter(2, 10, clock=lambda: t[0])
    rl.check(); rl.check()
    with pytest.raises(RateLimitedError):
        rl.check()
    t[0] = 11
    rl.check()


def test_secrets_on_disk_are_private_and_hashed(harness, env):
    creds = harness.pair()
    home = env["home"]
    assert mode(home) == 0o700
    for name in ("devices.json", "pairing.json", "local-token"):
        assert mode(home / name) == 0o600, name
    raw = (home / "devices.json").read_text()
    assert creds["token"] not in raw  # only the hash is stored
    pairing = (home / "pairing.json").read_text()
    assert "codes" in pairing


def test_revoked_device_loses_access(harness):
    creds = harness.pair()
    assert harness.client.delete(f"/api/v1/devices/{creds['deviceId']}", headers=harness.auth(creds)).status_code == 200
    assert harness.client.get("/api/v1/sessions", headers=harness.auth(creds)).status_code == 401


def test_device_can_only_update_its_own_fcm_token(harness):
    a = harness.pair("A")
    b = harness.pair("B")
    r = harness.client.put(f"/api/v1/devices/{b['deviceId']}/fcm-token", json={"fcmToken": "x" * 40}, headers=harness.auth(a))
    assert r.status_code == 403
    r = harness.client.put(f"/api/v1/devices/{a['deviceId']}/fcm-token", json={"fcmToken": "new-token-" + "y" * 30}, headers=harness.auth(a))
    assert r.status_code == 200
    devs = {d["deviceId"]: d for d in harness.app.state.devices.list_devices()}
    assert devs[a["deviceId"]]["fcmToken"].startswith("new-token-")
    assert devs[b["deviceId"]]["fcmToken"] == "f" * 40
    bad = harness.client.put(f"/api/v1/devices/{a['deviceId']}/fcm-token", json={"fcmToken": "bad token\n"}, headers=harness.auth(a))
    assert bad.status_code == 400


def test_notify_requires_local_token_not_device_token(harness):
    creds = harness.pair()
    body = {"sessionId": "s1", "title": "t", "body": "b", "kind": "completed"}
    assert harness.client.post("/api/v1/notify", json=body).status_code == 401
    assert harness.client.post("/api/v1/notify", json=body, headers=harness.auth(creds)).status_code == 401
    local = cfgmod.read_local_token()
    r = harness.client.post("/api/v1/notify", json=body, headers={"Authorization": f"Bearer {local}"})
    assert r.status_code == 200, r.text


def test_unexpected_host_header_rejected(harness):
    r = harness.client.get("/health", headers={"Host": "evil.example:18787"})
    assert r.status_code == 421


def test_configured_public_host_accepted(env, make_harness):
    cfgmod.save_server_config_value("publicBaseUrl", "https://mac.tail0.ts.net:8443")
    h = make_harness()
    assert h.client.get("/health", headers={"Host": "mac.tail0.ts.net:8443"}).status_code == 200


def test_oversized_body_rejected(harness):
    r = harness.client.post("/api/v1/pair", content=b"{" + b" " * 300_000 + b"}", headers={"Content-Type": "application/json"})
    assert r.status_code == 413


def test_device_store_concurrent_admin_and_server(env):
    a = DeviceStore(env["home"])
    b = DeviceStore(env["home"])
    codes = [a.create_pairing_code()[0], b.create_pairing_code()[0]]
    assert all(a.consume_pairing_code(c) for c in codes)
    assert json.loads((env["home"] / "pairing.json").read_text())["codes"] == []
