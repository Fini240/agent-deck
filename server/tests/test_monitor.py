import asyncio
import json
import subprocess
import sys

import pytest

from agentdeck import config as cfgmod
from agentdeck.events import EventBus
from agentdeck.keepawake import KeepAwake, default_command
from agentdeck.monitor import SessionHub, StatusTracker
from agentdeck.push import decrypt_payload

from .conftest import Clock, FakeKeepAwake, FakeTerminal


# ------------------------------------------------------------ status machine

def test_tracker_baseline_never_alerts():
    t = StatusTracker(debounce=5)
    assert t.observe("s", "error", 0) is None
    assert t.observe("s", "needs_input", 1) == "input"


def test_tracker_completion_is_debounced():
    t = StatusTracker(debounce=5)
    t.observe("s", "working", 0)
    assert t.observe("s", "idle", 1) is None
    assert t.observe("s", "idle", 4) is None
    assert t.is_working("s")  # still holding the sleep assertion while unconfirmed
    assert t.observe("s", "idle", 6.5) == "completed"
    assert not t.is_working("s")


def test_tracker_idle_flicker_between_tool_calls_does_not_complete():
    t = StatusTracker(debounce=5)
    t.observe("s", "working", 0)
    assert t.observe("s", "idle", 1) is None
    assert t.observe("s", "working", 3) is None
    assert t.observe("s", "idle", 4) is None
    assert t.observe("s", "idle", 8) is None  # pending restarted at 4
    assert t.observe("s", "idle", 9.1) == "completed"


def test_tracker_unknown_never_claims_completion():
    t = StatusTracker(debounce=0)
    t.observe("s", "working", 0)
    assert t.observe("s", "unknown", 1) is None
    assert t.observe("s", "unknown", 999) is None  # quiet transcript is not "done"
    assert t.observe("s", "idle", 1000) is None  # debounce starts
    assert t.observe("s", "idle", 1000) == "completed"


def test_tracker_error_and_input_immediate():
    t = StatusTracker(debounce=60)
    t.observe("s", "working", 0)
    assert t.observe("s", "error", 1) == "error"
    t.observe("s", "working", 2)
    assert t.observe("s", "needs_input", 3) == "input"
    assert t.observe("s", "needs_input", 4) is None


# ------------------------------------------------------------- hub harness

class Push:
    def __init__(self):
        self.payloads = []

    def deliver(self, payload, only_device=None):
        self.payloads.append(payload)


def make_hub(settings=None, debounce=5.0):
    clock = Clock(1000)
    term = FakeTerminal()
    push = Push()
    ka = FakeKeepAwake()
    s = dict(cfgmod.DEFAULT_SETTINGS)
    s["notifyOn"] = ["progress", "completed", "error", "input"]
    s.update(settings or {})
    hub = SessionHub(providers=None, terminal=term, get_settings=lambda: s, push=push, bus=None,
                     keepawake=ka, debounce=debounce, clock=clock, async_push=False)
    return hub, term, push, ka, clock, s


def kinds(push):
    return [p["type"] for p in push.payloads]


def test_hub_full_lifecycle_notifications_and_keepawake():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="idle", title="Fix tests", lastMessage="All 42 tests pass")
    hub.poll_once()
    assert push.payloads == [] and ka.held is False  # baseline

    term.sessions["m1"]["status"] = "working"
    term.sessions["m1"]["stage"] = "Running pytest"
    clock.advance(3); hub.poll_once()
    assert kinds(push) == ["progress"]  # first progress immediately, silent kind
    assert push.payloads[0]["progress"] is None  # unknown total -> no invented percentage
    assert ka.held is True

    term.sessions["m1"]["status"] = "idle"
    clock.advance(3); hub.poll_once()
    assert kinds(push) == ["progress"] and ka.held is True  # debounce pending
    clock.advance(6); hub.poll_once()
    assert kinds(push) == ["progress", "completed"]
    assert push.payloads[-1]["body"] == "All 42 tests pass"
    assert ka.held is False  # released on idle


def test_hub_progress_throttled_and_flushed():
    hub, term, push, ka, clock, _ = make_hub({"progressIntervalSeconds": 30})
    term.add("m1", status="idle")
    hub.poll_once()
    term.sessions["m1"].update(status="working", progress={"current": 1, "total": 10, "unit": "files"})
    clock.advance(1); hub.poll_once()
    for i in range(2, 6):
        term.sessions["m1"]["progress"] = {"current": i, "total": 10, "unit": "files"}
        clock.advance(3); hub.poll_once()
    assert kinds(push) == ["progress"]
    clock.advance(30); hub.poll_once()
    assert kinds(push) == ["progress", "progress"]
    assert push.payloads[-1]["progress"]["current"] == 5  # latest, not stale


def test_hub_error_releases_keepawake_and_alerts():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="working")
    hub.poll_once()
    assert ka.held
    term.sessions["m1"]["status"] = "error"
    clock.advance(1); hub.poll_once()
    assert "error" in kinds(push) and ka.held is False


def test_hub_respects_notify_on_and_keep_awake_off():
    hub, term, push, ka, clock, s = make_hub({"notifyOn": ["error"], "keepAwakeMode": "off"})
    term.add("m1", status="idle")
    hub.poll_once()
    term.sessions["m1"]["status"] = "working"
    clock.advance(1); hub.poll_once()
    assert ka.held is False
    term.sessions["m1"]["status"] = "idle"
    clock.advance(10); hub.poll_once(); clock.advance(10); hub.poll_once()
    assert push.payloads == []  # progress/completed not in notifyOn
    assert [n["type"] for n in hub.notifications] == ["progress", "completed"]  # still visible in-app


def test_hub_new_approval_alerts_once():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="working")
    hub.poll_once()
    term.sessions["m1"]["status"] = "needs_input"
    term.approval_map["m1"] = [{"id": "ap1", "title": "Allow Bash(npm test)?", "choices": [{"id": "y", "label": "Yes"}]}]
    clock.advance(1); hub.poll_once()
    clock.advance(3); hub.poll_once()
    inputs = [p for p in push.payloads if p["type"] == "input"]
    assert len(inputs) == 1
    assert ka.held is False  # waiting for the user is not "working"


def test_agent_notify_suppresses_duplicate_completion_and_holds_awake():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="idle", nativeId="native-1")
    hub.poll_once()
    hub.agent_notify("native-1", "Install", "npm ci", "progress", {"current": 2, "total": 4, "unit": None}, "deps")
    assert ka.held is True  # explicit progress lease keeps Mac awake
    sessions = {s["id"]: s for s in hub.refresh().sessions}
    assert sessions["m1"]["stage"] == "deps" and sessions["m1"]["progress"]["total"] == 4
    term.sessions["m1"]["status"] = "working"
    clock.advance(1); hub.poll_once()
    hub.agent_notify("m1", "Done", "installed", "completed", None, None)
    assert ka.held is True  # lease ended, but the terminal still reports working
    term.sessions["m1"]["status"] = "idle"
    clock.advance(1); hub.poll_once(); clock.advance(10); hub.poll_once()
    assert ka.held is False
    assert kinds(push).count("completed") == 1
    assert push.payloads[0]["sessionId"] == "m1"  # native id resolved to companion id


def test_notify_lease_expires(env):
    hub, term, push, ka, clock, _ = make_hub()
    hub.poll_once()
    hub.agent_notify("unknown-session", "Build", "", "progress", None, "compiling")
    assert ka.held
    assert push.payloads[0]["agent"] == "unknown"
    clock.advance(601); hub.poll_once()
    assert not ka.held


def test_hub_shutdown_releases():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="working")
    hub.poll_once()
    assert ka.held
    hub.shutdown()
    assert not ka.held


def test_notify_endpoint_end_to_end_encrypted(harness):
    creds = harness.pair(fcm_token="t" * 40)
    harness.terminal.add("m1", status="idle")
    harness.hub.poll_once()
    local = cfgmod.read_local_token()
    body = {"sessionId": "m1", "title": "Tests", "body": "3 failed", "kind": "error", "stage": "pytest"}
    r = harness.client.post("/api/v1/notify", json=body, headers={"Authorization": f"Bearer {local}"})
    assert r.status_code == 200
    _, msg = harness.sender.sent[-1]
    plain = decrypt_payload(creds["pushKey"], msg["message"]["data"])
    assert plain["type"] == "error" and plain["body"] == "3 failed" and plain["agent"] == "claude"
    assert set(plain) == {"eventId", "type", "sessionId", "title", "body", "agent", "timestamp", "progress", "stage", "canReply", "validForSeconds"}
    bad = dict(body, progress={"current": 5, "total": 3})
    assert harness.client.post("/api/v1/notify", json=bad, headers={"Authorization": f"Bearer {local}"}).status_code == 400


# ------------------------------------------------------------ real process

def test_keepawake_starts_and_really_terminates_child():
    ka = KeepAwake(lambda mode: [sys.executable, "-c", "import time; time.sleep(60)"])
    ka.update(True, "active")
    proc = ka._proc
    assert ka.held and proc.poll() is None
    ka.update(True, "active")
    assert ka._proc is proc  # idempotent, no second process
    ka.update(False, "active")
    assert not ka.held and proc.poll() is not None
    ka.update(True, "off")
    assert not ka.held


@pytest.mark.skipif(sys.platform != "darwin", reason="macOS caffeinate")
def test_keepawake_real_caffeinate_assertion_released():
    ka = KeepAwake()
    cmd = default_command("active")
    assert cmd[:2] == ["/usr/bin/caffeinate", "-i"] and "-w" in cmd
    ka.update(True, "active")
    try:
        out = subprocess.run(["pmset", "-g", "assertions"], capture_output=True, text=True, timeout=10).stdout
        assert f"pid {ka._proc.pid}(caffeinate)" in out
    finally:
        ka.release()
    out = subprocess.run(["pmset", "-g", "assertions"], capture_output=True, text=True, timeout=10).stdout
    assert "(caffeinate)" not in out or f"pid {ka._proc}" not in out
    assert not ka.held


# ------------------------------------------------------------------- events

def test_event_bus_thread_safe_and_bounded():
    async def run():
        bus = EventBus(max_queue=3)
        bus.bind_loop(asyncio.get_running_loop())
        q = bus.subscribe()
        await asyncio.to_thread(lambda: [bus.publish("sessions", f"s{i}") for i in range(5)])
        await asyncio.sleep(0.05)
        got = [json.loads(q.get_nowait()) for _ in range(q.qsize())]
        bus.unsubscribe(q)
        return got

    got = asyncio.run(run())
    assert [g["sessionId"] for g in got] == ["s2", "s3", "s4"]
    assert got[0]["type"] == "sessions"


def test_subagents_never_alert_or_hold_awake():
    from .conftest import FakeProviders

    hub, term, push, ka, clock, _ = make_hub()
    prov = FakeProviders()
    hub.providers = prov
    prov.sessions = [{"id": "sub1", "agent": "claude", "nativeId": "a1", "isSubagent": True, "status": "idle",
                      "updatedAt": "2026-10-07T00:00:00Z"}]
    hub.poll_once()
    prov.sessions[0]["status"] = "working"
    clock.advance(1); hub.poll_once()
    assert ka.held is False and push.payloads == []
    prov.sessions[0]["status"] = "error"
    clock.advance(10); hub.poll_once()
    assert push.payloads == []


def test_default_settings_push_background_progress():
    hub, term, push, ka, clock, s = make_hub()
    s["notifyOn"] = list(cfgmod.DEFAULT_SETTINGS["notifyOn"])  # what a fresh install uses
    term.add("m1", status="idle")
    hub.poll_once()
    term.sessions["m1"].update(status="working", stage="Running pytest")
    clock.advance(3); hub.poll_once()
    assert kinds(push) == ["progress"]  # the phone's silent ongoing notification needs this push


def test_throttled_progress_is_not_pushed_after_work_ends():
    hub, term, push, ka, clock, _ = make_hub({"progressIntervalSeconds": 30})
    term.add("m1", status="idle")
    hub.poll_once()
    term.sessions["m1"].update(status="working", stage="npm ci")
    clock.advance(1); hub.poll_once()
    term.sessions["m1"]["stage"] = "npm test"  # throttled, waits for the interval
    clock.advance(1); hub.poll_once()
    term.sessions["m1"].update(status="offline", stage="Terminal closed")  # pane closed mid-work
    clock.advance(1); hub.poll_once()
    clock.advance(60); hub.poll_once()
    assert kinds(push) == ["progress"]  # no stale "working: npm test" after the session went away


def test_throttled_progress_dropped_when_agent_hook_already_completed():
    hub, term, push, ka, clock, _ = make_hub({"progressIntervalSeconds": 30})
    term.add("m1", status="idle", nativeId="n1")
    hub.poll_once()
    term.sessions["m1"].update(status="working", stage="build")
    clock.advance(1); hub.poll_once()
    hub.agent_notify("n1", "Claude finished", "", "completed", None, None)
    term.sessions["m1"]["stage"] = "wrapping up"  # screen lags behind the Stop hook
    clock.advance(1); hub.poll_once()
    term.sessions["m1"].update(status="idle", stage=None)
    for _ in range(10):
        clock.advance(6); hub.poll_once()
    assert kinds(push) == ["progress", "completed"]


def test_agent_notify_for_subagent_never_rings_as_its_own_chat():
    from .conftest import FakeProviders

    hub, term, push, ka, clock, _ = make_hub()
    prov = FakeProviders()
    hub.providers = prov
    prov.sessions = [
        {"id": "codex:parent", "agent": "codex", "nativeId": "t-parent", "status": "working",
         "updatedAt": "2026-10-07T00:00:00Z"},
        {"id": "codex-sub:t-child", "agent": "codex", "nativeId": "t-child", "isSubagent": True,
         "parentSessionId": "codex:parent", "status": "working", "updatedAt": "2026-10-07T00:00:01Z"},
    ]
    hub.poll_once()
    # Codex `notify` fires agent-turn-complete for the child thread while the parent keeps working.
    hub.agent_notify("t-child", "Codex finished", "child done", "completed", None, None)
    hub.agent_notify("t-child", "Indexing", "", "progress", {"current": 1, "total": 2, "unit": None}, None)
    assert push.payloads == []
    assert [n["sessionId"] for n in hub.notifications] == ["codex-sub:t-child"] * 2  # still visible in-app
    hub.agent_notify("t-child", "Codex needs input", "approve rm?", "input", None, None)
    assert kinds(push) == ["input"] and push.payloads[0]["sessionId"] == "codex:parent"  # controllable chat


def test_notify_requests_and_poll_thread_do_not_race():
    import threading

    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="working")
    hub.poll_once()
    errors, stop = [], threading.Event()
    old = sys.getswitchinterval()
    sys.setswitchinterval(1e-6)

    def notifier():
        i = 0
        while not stop.is_set():
            i += 1
            try:
                hub.agent_notify(f"hook-{i}", "Build", "", "progress", None, None)
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)
                return

    def poller():
        while not stop.is_set():
            try:
                hub.poll_once()
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)
                return

    threads = [threading.Thread(target=notifier), threading.Thread(target=poller)]
    try:
        for t in threads:
            t.start()
        stop.wait(3)
    finally:
        stop.set()
        for t in threads:
            t.join()
        sys.setswitchinterval(old)
    assert errors == []


def test_long_unchanged_work_gets_real_heartbeats_but_offline_does_not():
    hub, term, push, ka, clock, _ = make_hub()
    term.add("m1", status="idle")
    hub.poll_once()
    term.sessions["m1"]["status"] = "working"
    clock.advance(3); hub.poll_once()
    assert kinds(push) == ["progress"]
    clock.advance(61); hub.poll_once()
    assert kinds(push) == ["progress", "progress"]
    assert push.payloads[-1]["validForSeconds"] >= 180
    term.sessions["m1"]["status"] = "offline"
    clock.advance(61); hub.poll_once()
    assert kinds(push) == ["progress", "progress"]
