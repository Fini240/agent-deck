import base64
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from agentdeck.errors import DeliveryUncertainError, NotReadyError, UnavailableError
from agentdeck.idempotency import IdempotencyStore
from agentdeck.monitor import SessionHub, clean_progress
from agentdeck.push import decrypt_payload, encrypt_payload


def test_crash_during_submission_leaves_a_durable_no_resend_record(tmp_path):
    path = tmp_path / "requests.json"
    first = IdempotencyStore(path)
    delivered = []

    def send():
        delivered.append("first")
        # A freshly restarted helper sees the intent before the old call returns.
        restarted = IdempotencyStore(path)
        with pytest.raises(Exception, match="Check the chat"):
            restarted.run("device:send:request-1", lambda: delivered.append("duplicate"))
        return {"accepted": True}

    assert first.run("device:send:request-1", send) == {"accepted": True}
    assert delivered == ["first"]
    assert IdempotencyStore(path).run("device:send:request-1", lambda: delivered.append("duplicate")) == {"accepted": True}
    assert delivered == ["first"]


def test_duplicate_inflight_waits_for_confirmed_result(tmp_path):
    store = IdempotencyStore(tmp_path / "requests.json")
    entered, release = threading.Event(), threading.Event()
    def send():
        entered.set()
        assert release.wait(3)
        return {"accepted": True}
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(store.run, "same-request", send)
        assert entered.wait(3)
        second = pool.submit(store.run, "same-request", lambda: pytest.fail("duplicate submission"))
        release.set()
        assert first.result(3) == second.result(3) == {"accepted": True}


def test_journal_failure_never_touches_terminal(tmp_path, monkeypatch):
    store = IdempotencyStore(tmp_path / "requests.json")
    monkeypatch.setattr(store, "_persist", lambda: (_ for _ in ()).throw(OSError("disk full")))
    with pytest.raises(UnavailableError, match="nothing was sent"):
        store.run("request-1", lambda: pytest.fail("terminal touched without durable journal"))


def test_proven_presubmit_failure_can_retry_after_restart(tmp_path):
    path = tmp_path / "requests.json"
    with pytest.raises(NotReadyError):
        IdempotencyStore(path).run("request-1", lambda: (_ for _ in ()).throw(NotReadyError("not ready")))
    assert IdempotencyStore(path).run("request-1", lambda: "sent") == "sent"


def test_unicode_push_fits_fcm_and_preserves_event_identity():
    key = base64.b64encode(bytes(range(32))).decode()
    payload = {"eventId": "event-1", "sessionId": "managed-1", "type": "completed",
               "timestamp": "2026-10-07T09:00:00Z", "canReply": True,
               "title": "😀" * 200, "stage": "漢字" * 200, "body": "😀" * 1000}
    data = encrypt_payload(key, payload)
    assert sum(len(k.encode()) + len(v.encode()) for k, v in data.items()) <= 4096
    opened = decrypt_payload(key, data)
    for field in ("eventId", "sessionId", "type", "timestamp", "canReply"):
        assert opened[field] == payload[field]
    assert opened["body"] != payload["body"]


def test_progress_rejects_nonfinite_numbers():
    assert clean_progress({"current": float("nan"), "total": 10}) is None
    assert clean_progress({"current": 1, "total": float("inf")}) is None


@pytest.mark.parametrize("managed,child,send,expected", [(True, False, True, True), (False, False, True, False), (True, True, True, False), (True, False, False, False)])
def test_notification_reply_requires_actual_control(managed, child, send, expected):
    hub = SessionHub(providers=None, terminal=None, get_settings=lambda: {}, push=None, bus=None, keepawake=None)
    session = {"id": "s", "agent": "claude", "managed": managed, "isSubagent": child, "capabilities": {"send": send}}
    assert hub._payload("completed", session, "s", "done", "", None, None)["canReply"] is expected
    assert hub._payload("input", session, "s", "input", "", None, None)["canReply"] is False
    assert hub._payload("progress", None, "unknown", "", "", None, None)["canReply"] is False
    hub.shutdown()
