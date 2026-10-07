import base64
import json
import os

import pytest
from cryptography.exceptions import InvalidTag

from agentdeck.auth import DeviceStore
from agentdeck.push import FcmSender, PushService, build_fcm_message, decrypt_payload, encrypt_payload

from .conftest import FIXTURES, FakeSender

ANDROID_FIXTURE = FIXTURES.parents[2] / "android/core/push/src/test/resources/push-fixture.json"


def _fixtures():
    paths = [FIXTURES / "push-fixture.json"]
    if ANDROID_FIXTURE.exists():
        paths.append(ANDROID_FIXTURE)
    return paths


@pytest.mark.parametrize("path", _fixtures(), ids=lambda p: p.parent.name)
def test_encryption_matches_shared_android_fixture(path):
    fx = json.loads(path.read_text())
    for case in fx["cases"]:
        payload = json.loads(case["plaintext"])
        nonce = base64.b64decode(case["data"]["nonce"])
        assert encrypt_payload(fx["keyBase64"], payload, nonce=nonce) == case["data"], case["name"]
        assert decrypt_payload(fx["keyBase64"], case["data"]) == payload
    with pytest.raises(InvalidTag):
        decrypt_payload(fx["keyBase64"], fx["tampered"])


def test_roundtrip_random_nonce_and_wrong_key_fails():
    key = base64.b64encode(os.urandom(32)).decode()
    other = base64.b64encode(os.urandom(32)).decode()
    payload = {"eventId": "e", "type": "input", "title": "Approve?", "progress": None}
    a, b = encrypt_payload(key, payload), encrypt_payload(key, payload)
    assert a["nonce"] != b["nonce"]
    assert decrypt_payload(key, a) == payload
    with pytest.raises(InvalidTag):
        decrypt_payload(other, a)


def test_fcm_message_carries_only_ciphertext():
    key = base64.b64encode(bytes(32)).decode()
    payload = {"type": "completed", "title": "SECRET-TITLE", "body": "SECRET-BODY", "sessionId": "s1"}
    msg = build_fcm_message("tok", encrypt_payload(key, payload), "completed", "s1")
    m = msg["message"]
    assert "notification" not in m and "notification" not in m["android"]
    assert set(m["data"]) == {"v", "nonce", "ciphertext"}
    assert all(isinstance(v, str) for v in m["data"].values())
    assert "SECRET" not in json.dumps(msg)


def test_progress_messages_collapse_per_session_alerts_do_not():
    key = base64.b64encode(bytes(32)).decode()
    enc = encrypt_payload(key, {})
    assert build_fcm_message("t", enc, "progress", "s1")["message"]["android"]["collapse_key"] == "p_s1"
    assert "collapse_key" not in build_fcm_message("t", enc, "completed", "s1")["message"]["android"]


def test_push_service_encrypts_per_device_and_drops_unregistered(env):
    store = DeviceStore(env["home"])
    creds = []
    for tok in ("a" * 40, "b" * 40):
        code, _ = store.create_pairing_code()
        creds.append(store.pair(code, "d", tok))
    sender = FakeSender(outcome="unregistered")
    svc = PushService(store, sender)
    payload = {"eventId": "1", "type": "error", "sessionId": "s", "title": "x", "body": "y"}
    res = svc.deliver(payload)
    assert res["unregistered"] == 2
    for (token, msg), cred in zip(sender.sent, creds):
        assert decrypt_payload(cred["pushKey"], msg["message"]["data"]) == payload
    assert all(d["fcmToken"] is None for d in store.list_devices())


def test_fcm_sender_reports_missing_credentials_clearly(env):
    st = FcmSender(env["home"] / "firebase-service-account.json").status()
    assert not st.available and "not configured" in st.reason
    assert FcmSender(env["home"] / "nope.json").send("t", {}) == "unavailable"


def test_fcm_sender_refuses_world_readable_credentials(env):
    path = env["home"] / "sa.json"
    env["home"].mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"type": "service_account", "project_id": "p", "private_key": "-----BEGIN PRIVATE KEY-----\nSECRET\n"}))
    os.chmod(path, 0o644)
    st = FcmSender(path).status()
    assert not st.available and "0600" in st.reason
    os.chmod(path, 0o600)
    st = FcmSender(path).status()
    assert not st.available
    assert "SECRET" not in st.reason  # parse failure never leaks key material


def test_fcm_disabled_in_config(env):
    assert "disabled" in FcmSender(env["home"] / "x.json", enabled=False).status().reason


def test_push_test_endpoint_reports_unavailable(make_harness):
    h = make_harness(sender=FakeSender(available=False))
    creds = h.pair()
    r = h.client.post("/api/v1/push/test", headers=h.auth(creds))
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "push_unavailable"
    st = h.client.get("/api/v1/status", headers=h.auth(creds)).json()
    assert st["push"]["available"] is False and st["push"]["reason"]


def test_push_test_endpoint_sends_encrypted_to_caller_only(harness):
    a = harness.pair("A", fcm_token="a" * 40)
    harness.pair("B", fcm_token="b" * 40)
    r = harness.client.post("/api/v1/push/test", headers=harness.auth(a))
    assert r.status_code == 200 and r.json()["result"]["ok"] == 1
    token, msg = harness.sender.sent[-1]
    assert token == "a" * 40
    assert decrypt_payload(a["pushKey"], msg["message"]["data"])["type"] == "completed"
