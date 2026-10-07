import asyncio
import contextlib
import os
import stat

import httpx

import pytest

from .conftest import FakeTerminal
from agentdeck.errors import NotReadyError


@pytest.fixture
def h(harness):
    harness.creds = harness.pair()
    harness.H = harness.auth(harness.creds)
    return harness


def disc(sid, native, agent="claude", status="idle", **kw):
    return {"id": sid, "agent": agent, "nativeId": native, "title": f"chat {native}", "cwd": "/w",
            "model": "m", "status": status, "updatedAt": "2026-10-07T00:00:01.000Z",
            "capabilities": {"send": True, "interrupt": True, "approve": True, "stop": True}, **kw}


# ------------------------------------------------------------------ sessions

def test_sessions_merge_managed_and_discovered(h):
    h.providers.sessions = [disc("claude:n1", "n1", lastMessage="hi"), disc("codex:n2", "n2", agent="codex")]
    h.terminal.add("m1", nativeId="n1", status="working")
    sessions = h.client.get("/api/v1/sessions", headers=h.H).json()["sessions"]
    by_id = {s["id"]: s for s in sessions}
    assert set(by_id) == {"m1", "codex:n2"}
    assert by_id["m1"]["managed"] and by_id["m1"]["status"] == "working"
    assert by_id["m1"]["lastMessage"] == "hi"  # discovered transcript fields folded in
    # Unmanaged sessions never advertise control, even if the provider claims it.
    assert by_id["codex:n2"]["capabilities"] == {"send": False, "interrupt": False, "approve": False, "stop": False}
    assert set(by_id["m1"]) == {"id", "agent", "nativeId", "title", "cwd", "model", "status", "stage", "progress",
                                "updatedAt", "managed", "capabilities", "lastMessage", "unread",
                                # optional additive fields
                                "parentSessionId", "agentName", "agentRole", "isSubagent", "task", "projectRoot",
                                "statusEvidence", "live", "canResume"}
    assert "_transcriptPath" not in by_id["m1"]  # private provider metadata stripped
    codex = h.client.get("/api/v1/sessions?agent=codex", headers=h.H).json()["sessions"]
    assert [s["id"] for s in codex] == ["codex:n2"]
    assert h.client.get("/api/v1/sessions?agent=rm", headers=h.H).status_code == 400


def test_discovered_alias_resolves_to_managed_session(h):
    h.providers.sessions = [disc("claude:n1", "n1")]
    h.terminal.add("m1", nativeId="n1")
    r = h.client.post("/api/v1/sessions/claude:n1/send", json={"text": "hi", "requestId": "req-00001"}, headers=h.H)
    assert r.status_code == 200 and r.json()["sessionId"] == "m1"
    assert h.terminal.calls == [("send", "m1", "hi", True)]


def test_invalid_progress_is_never_invented(h):
    h.terminal.add("m1", progress={"current": 5, "total": 0})
    h.terminal.add("m2", progress={"current": 2, "total": 4, "unit": "steps"})
    by_id = {s["id"]: s for s in h.client.get("/api/v1/sessions", headers=h.H).json()["sessions"]}
    assert by_id["m1"]["progress"] is None
    assert by_id["m2"]["progress"] == {"current": 2, "total": 4, "unit": "steps"}


def test_messages_bounded_and_redacted(h):
    h.providers.sessions = [disc("claude:n1", "n1")]
    h.providers.messages["n1"] = [
        {"id": str(i), "role": "assistant", "text": f"msg {i}", "timestamp": "t"} for i in range(300)
    ] + [{"id": "k", "role": "weird", "text": "key sk-ant-api03-ABCDEFGHIJKLMNOP", "timestamp": "t"}]
    msgs = h.client.get("/api/v1/sessions/claude:n1/messages?limit=5", headers=h.H).json()["messages"]
    assert len(msgs) == 5
    assert msgs[-1]["role"] == "system"
    assert "ABCDEFGHIJ" not in msgs[-1]["text"]
    assert h.client.get("/api/v1/sessions/nope/messages", headers=h.H).status_code == 404
    assert h.client.get("/api/v1/sessions/..%2F..%2Fetc/messages", headers=h.H).status_code in (400, 404)


def test_terminal_preview_redacts_and_is_bounded(h):
    h.terminal.add("m1")
    h.terminal.preview_text = "x\n" * 50_000 + "export OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz123\nAuthorization: Bearer abcdefghijklmnopqrstuvwxyz\n"
    body = h.client.get("/api/v1/sessions/m1/terminal", headers=h.H).json()
    assert len(body["text"]) <= 64_000
    assert "abcdefghijklmnopqrstuvwxyz" not in body["text"]
    h.providers.sessions = [disc("claude:n9", "n9")]
    assert h.client.get("/api/v1/sessions/claude:n9/terminal", headers=h.H).json() == {"text": "", "available": False}


# ---------------------------------------------------------------------- send

def test_send_to_unmanaged_session_is_refused(h):
    h.providers.sessions = [disc("claude:n1", "n1", status="working")]
    r = h.client.post("/api/v1/sessions/claude:n1/send", json={"text": "hi", "requestId": "req-00001"}, headers=h.H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "not_managed"
    assert h.terminal.calls == []


def test_send_is_idempotent_per_request_id(h):
    h.terminal.add("m1")
    for _ in range(3):
        r = h.client.post("/api/v1/sessions/m1/send", json={"text": "follow up", "requestId": "req-aaaa1"}, headers=h.H)
        assert r.json() == {"accepted": True, "sessionId": "m1"}
    h.client.post("/api/v1/sessions/m1/send", json={"text": "second", "requestId": "req-bbbb2", "interrupt": False}, headers=h.H)
    assert h.terminal.calls == [("send", "m1", "follow up", True), ("send", "m1", "second", False)]


def test_idempotency_survives_restart(env, make_harness):
    term = FakeTerminal()
    term.add("m1")
    h1 = make_harness(terminal=term)
    creds = h1.pair()
    h1.client.post("/api/v1/sessions/m1/send", json={"text": "once", "requestId": "req-restart1"}, headers=h1.auth(creds))
    h1.close()
    h2 = make_harness(terminal=term)
    r = h2.client.post("/api/v1/sessions/m1/send", json={"text": "once", "requestId": "req-restart1"}, headers=h2.auth(creds))
    assert r.status_code == 200
    assert [c for c in term.calls if c[0] == "send"] == [("send", "m1", "once", True)]


def test_failed_send_is_not_cached(h):
    h.terminal.add("m1")
    original = h.terminal.send
    state = {"n": 0}

    def flaky(sid, text, interrupt=True):
        state["n"] += 1
        if state["n"] == 1:
            raise NotReadyError("not ready")
        original(sid, text, interrupt)

    h.terminal.send = flaky
    r1 = h.client.post("/api/v1/sessions/m1/send", json={"text": "x", "requestId": "req-flaky1"}, headers=h.H)
    assert r1.status_code == 409 and r1.json()["error"]["code"] == "not_ready"
    r2 = h.client.post("/api/v1/sessions/m1/send", json={"text": "x", "requestId": "req-flaky1"}, headers=h.H)
    assert r2.status_code == 200


def _uncertain_send(term):
    """Terminal that types the text and presses Enter, then cannot confirm submission."""
    from agentdeck.errors import DeliveryUncertainError

    def send(sid, text, interrupt=True):
        term.calls.append(("send", sid, text, interrupt))
        raise DeliveryUncertainError("message pasted but the terminal did not confirm submission")

    term.send = send


def test_uncertain_send_is_not_typed_again_on_retry(h):
    h.terminal.add("m1")
    _uncertain_send(h.terminal)
    for _ in range(3):  # WorkManager / user retries with the same requestId
        r = h.client.post("/api/v1/sessions/m1/send", json={"text": "deploy", "requestId": "req-unsure1"}, headers=h.H)
        assert r.status_code == 409 and r.json()["error"]["code"] == "delivery_uncertain"
    assert h.terminal.calls == [("send", "m1", "deploy", True)]
    # A new request (explicit user resend) is still allowed.
    h.client.post("/api/v1/sessions/m1/send", json={"text": "deploy", "requestId": "req-unsure2"}, headers=h.H)
    assert len(h.terminal.calls) == 2


def test_uncertain_send_is_not_typed_again_after_restart(env, make_harness):
    term = FakeTerminal()
    term.add("m1")
    _uncertain_send(term)
    h1 = make_harness(terminal=term)
    creds = h1.pair()
    body = {"text": "once", "requestId": "req-unsure3"}
    assert h1.client.post("/api/v1/sessions/m1/send", json=body, headers=h1.auth(creds)).status_code == 409
    h1.close()
    h2 = make_harness(terminal=term)
    r = h2.client.post("/api/v1/sessions/m1/send", json=body, headers=h2.auth(creds))
    assert r.status_code == 409 and r.json()["error"]["code"] == "delivery_uncertain"
    assert len(term.calls) == 1


@pytest.mark.parametrize(
    "text",
    ["\x1b[201~rm -rf ~\n", "hi\x03", "a\x00b", "ok\x1b", "\x9b201~", "   ", "x" * 32_001],
)
def test_send_rejects_terminal_control_sequences(h, text):
    h.terminal.add("m1")
    r = h.client.post("/api/v1/sessions/m1/send", json={"text": text, "requestId": "req-ctrl01"}, headers=h.H)
    assert r.status_code == 400
    assert h.terminal.calls == []


def test_shell_metacharacters_are_passed_as_literal_text(h):
    h.terminal.add("m1")
    text = "$(touch /tmp/pwned); `id` && rm -rf ~ | tee x\r\nsecond line\ttab"
    assert h.client.post("/api/v1/sessions/m1/send", json={"text": text, "requestId": "req-meta01"}, headers=h.H).status_code == 200
    assert h.terminal.calls[-1][2] == text.replace("\r\n", "\n")


def test_send_requires_valid_request_id(h):
    h.terminal.add("m1")
    for rid in (None, "short", "has space 123", "x" * 200):
        r = h.client.post("/api/v1/sessions/m1/send", json={"text": "hi", "requestId": rid}, headers=h.H)
        assert r.status_code == 400


def test_unknown_terminal_errors_are_generic(h):
    h.terminal.add("m1")

    def boom(*a, **k):
        raise RuntimeError("/Users/example/secret path in traceback")

    h.terminal.send = boom
    r = h.client.post("/api/v1/sessions/m1/send", json={"text": "hi", "requestId": "req-boom01"}, headers=h.H)
    assert r.status_code == 500
    assert "secret" not in r.text


# ----------------------------------------------------------------- approvals

def test_approvals_stale_and_valid(h):
    h.terminal.add("m1", status="needs_input")
    h.terminal.approval_map["m1"] = [{"id": "ap1", "title": "Run bash?", "detail": "npm test",
                                      "choices": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}],
                                      "createdAt": "2026-10-07T00:00:00Z"}]
    listed = h.client.get("/api/v1/sessions/m1/approvals", headers=h.H).json()["approvals"]
    assert listed[0]["sessionId"] == "m1" and [c["id"] for c in listed[0]["choices"]] == ["yes", "no"]
    r = h.client.post("/api/v1/sessions/m1/approvals/ap1", json={"choiceId": "always", "requestId": "req-appr01"}, headers=h.H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "stale_approval"
    r = h.client.post("/api/v1/sessions/m1/approvals/old", json={"choiceId": "yes", "requestId": "req-appr02"}, headers=h.H)
    assert r.status_code == 409 and r.json()["error"]["code"] == "stale_approval"
    r = h.client.post("/api/v1/sessions/m1/approvals/ap1", json={"choiceId": "yes", "requestId": "req-appr03"}, headers=h.H)
    assert r.status_code == 200
    assert ("approve", "m1", "ap1", "yes") in h.terminal.calls
    # Prompt answered -> the same approval is now stale.
    r = h.client.post("/api/v1/sessions/m1/approvals/ap1", json={"choiceId": "yes", "requestId": "req-appr04"}, headers=h.H)
    assert r.status_code == 409


def test_input_keys_whitelisted_and_managed_only(h):
    h.terminal.add("m1")
    assert h.client.post("/api/v1/sessions/m1/input", json={"key": "enter"}, headers=h.H).status_code == 200
    assert h.client.post("/api/v1/sessions/m1/input", json={"key": "C-c"}, headers=h.H).status_code == 400
    h.providers.sessions = [disc("claude:n1", "n1")]
    assert h.client.post("/api/v1/sessions/claude:n1/input", json={"key": "enter"}, headers=h.H).status_code == 409
    assert h.terminal.calls == [("input", "m1", "enter")]


def test_stop_preserves_session_and_is_idempotent(h):
    h.terminal.add("m1", status="working")
    for _ in range(2):
        assert h.client.post("/api/v1/sessions/m1/stop", json={"requestId": "req-stop01"}, headers=h.H).status_code == 200
    assert h.terminal.calls == [("stop", "m1")]


# ------------------------------------------------------------ start / resume

def test_start_session_validates_workspace(h, env):
    ok = env["user"] / "projects" / "app"
    r = h.client.post("/api/v1/sessions", json={"agent": "codex", "cwd": str(ok), "prompt": "hello", "model": "gpt-x"}, headers=h.H)
    assert r.status_code == 200, r.text
    assert r.json()["session"]["managed"] is True
    assert h.terminal.calls[-1] == ("start", "codex", str(ok), "gpt-x", "hello")

    bad_cases = [
        {"agent": "codex", "cwd": "/"},
        {"agent": "codex", "cwd": "relative/path"},
        {"agent": "codex", "cwd": str(env["tmp"])},  # outside allowed home
        {"agent": "codex", "cwd": str(env["home"])},  # agent-deck secrets dir
        {"agent": "bash", "cwd": str(ok)},
        {"agent": "codex", "cwd": str(ok), "model": "gpt; rm -rf ~"},
        {"agent": "codex", "cwd": str(ok), "prompt": "\x1b[201~"},
    ]
    escape = env["user"] / "projects" / "link"
    os.symlink(env["tmp"], escape)
    bad_cases.append({"agent": "codex", "cwd": str(escape)})
    (env["user"] / ".ssh").mkdir()
    bad_cases.append({"agent": "claude", "cwd": str(env["user"] / ".ssh")})
    for body in bad_cases:
        r = h.client.post("/api/v1/sessions", json=body, headers=h.H)
        assert r.status_code == 400, body
    assert len([c for c in h.terminal.calls if c[0] == "start"]) == 1


def test_start_uses_default_model(h, env):
    h.client.patch("/api/v1/settings", json={"defaultModels": {"claude": "live-model-1"}}, headers=h.H)
    r = h.client.post("/api/v1/sessions", json={"cwd": str(env["user"])}, headers=h.H)
    assert r.status_code == 200
    assert h.terminal.calls[-1] == ("start", "claude", str(env["user"]), "live-model-1", None)


def test_resume_refuses_busy_external_chat(h, env):
    h.providers.sessions = [disc("claude:n1", "n1", status="working", cwd=str(env["user"]))]
    r = h.client.post("/api/v1/sessions/claude:n1/resume", json={}, headers=h.H)
    assert r.status_code == 409
    h.providers.sessions = [disc("claude:n1", "n1", status="idle", cwd=str(env["user"]))]
    r = h.client.post("/api/v1/sessions/claude:n1/resume", json={"requestId": "req-resume1"}, headers=h.H)
    assert r.status_code == 200 and r.json()["session"]["managed"], r.text
    # Now merged: the discovered id is an alias of the managed session.
    ids = [s["id"] for s in h.client.get("/api/v1/sessions", headers=h.H).json()["sessions"]]
    assert ids == ["m-resumed"]


# ------------------------------------------------------------ settings/models

def test_settings_defaults_and_safe_patch(h, env):
    s = h.client.get("/api/v1/settings", headers=h.H).json()["settings"]
    assert s["allowedWorkspaces"] == [str(env["user"])]
    assert s["keepAwakeMode"] == "active"
    for patch in ({"allowedWorkspaces": ["/"]}, {"allowedWorkspaces": [str(env["tmp"])]}, {"claudePath": "/bin/sh"},
                  {"progressIntervalSeconds": 1}, {"keepAwakeMode": "always"}, {"notifyOn": ["spam"]}):
        assert h.client.patch("/api/v1/settings", json=patch, headers=h.H).status_code == 400, patch
    proj = str(env["user"] / "projects")
    r = h.client.patch("/api/v1/settings", json={"allowedWorkspaces": [proj], "keepAwakeMode": "off", "notifyOn": ["error", "completed"]}, headers=h.H)
    assert r.status_code == 200
    assert r.json()["settings"]["allowedWorkspaces"] == [proj]
    assert r.json()["settings"]["notifyOn"] == ["completed", "error"]
    assert stat.S_IMODE(os.stat(env["home"] / "settings.json").st_mode) == 0o600
    # Narrowed workspace now applies to session start.
    r = h.client.post("/api/v1/sessions", json={"agent": "claude", "cwd": str(env["user"])}, headers=h.H)
    assert r.status_code == 400


def test_models_cached_until_refresh(h):
    h.client.get("/api/v1/models", headers=h.H)
    h.client.get("/api/v1/models", headers=h.H)
    r = h.client.post("/api/v1/models/refresh", headers=h.H)
    assert r.json()["agents"][0]["models"][0]["id"] == "live-model-1"
    assert h.providers.model_calls == [False, True]


def test_providers_missing_is_reported_not_crashing(make_harness):
    h = make_harness(providers=None, terminal=None)
    creds = h.pair()
    assert h.client.get("/api/v1/sessions", headers=h.auth(creds)).json() == {"sessions": []}
    st = h.client.get("/api/v1/status", headers=h.auth(creds)).json()
    assert st["terminal"]["available"] is False
    r = h.client.post("/api/v1/sessions", json={"agent": "claude", "cwd": str(h.env["user"])}, headers=h.auth(creds))
    assert r.status_code == 503


# ------------------------------------------------------------------ download

def test_apk_download_requires_expiring_link(make_harness, env):
    from agentdeck import config as cfgmod

    apk_dir = env["tmp"] / "outputs"
    apk_dir.mkdir()
    (apk_dir / "agent-deck-debug.apk").write_bytes(b"PK\x03\x04fake")
    (env["tmp"] / "secret.apk").write_bytes(b"nope")
    os.symlink(env["tmp"] / "secret.apk", apk_dir / "escape.apk")
    cfgmod.save_server_config_value("apkDir", str(apk_dir))
    h = make_harness()
    assert h.client.get("/download/guess").status_code == 404
    token, _ = h.app.state.downloads.create()
    page = h.client.get(f"/download/{token}")
    assert page.status_code == 200 and "agent-deck-debug.apk" in page.text and "escape.apk" not in page.text
    f = h.client.get(f"/download/{token}/agent-deck-debug.apk")
    assert f.status_code == 200 and f.content == b"PK\x03\x04fake"
    assert f.headers["content-type"] == "application/vnd.android.package-archive"
    assert h.client.get(f"/download/{token}/escape.apk").status_code == 404
    assert h.client.get(f"/download/{token}/..%2Fsecret.apk").status_code == 404
    h.clock.advance(3600)
    assert h.client.get(f"/download/{token}").status_code == 404


# ----------------------------------------------------------------- subagents

def test_subagents_keep_identity_and_resolve_parent(h):
    h.providers.sessions = [
        disc("claude:p1", "p1", status="working", projectRoot="/w"),
        disc("claude-sub:p1:a1", "a1", status="working", isSubagent=True, parentSessionId="p1",
             agentName="Explore repo", agentRole="Explore", task="find x sk-ant-api03-ABCDEFGHIJKLMNOP"),
        # A child whose native id collides with a managed native id must not be merged.
        disc("claude-sub:p1:n1", "n1", status="completed", isSubagent=True, parentSessionId="claude:p1"),
    ]
    h.terminal.add("m1", nativeId="p1", status="working")
    h.terminal.add("m2", nativeId="n1", status="idle")
    by_id = {s["id"]: s for s in h.client.get("/api/v1/sessions", headers=h.H).json()["sessions"]}
    assert set(by_id) == {"m1", "m2", "claude-sub:p1:a1", "claude-sub:p1:n1"}
    child = by_id["claude-sub:p1:a1"]
    assert child["isSubagent"] and child["parentSessionId"] == "m1"  # native parent -> managed id
    assert by_id["claude-sub:p1:n1"]["parentSessionId"] == "m1"  # alias -> managed id
    assert child["agentName"] == "Explore repo" and "ABCDEFGHIJ" not in child["task"]
    assert child["capabilities"] == {"send": False, "interrupt": False, "approve": False, "stop": False}
    assert child["canResume"] is False and by_id["m1"]["isSubagent"] is False
    r = h.client.post("/api/v1/sessions/claude-sub:p1:a1/send", json={"text": "x", "requestId": "req-child1"}, headers=h.H)
    assert r.status_code == 409
    assert h.client.post("/api/v1/sessions/claude-sub:p1:a1/resume", json={}, headers=h.H).status_code == 501


def test_resume_refuses_live_external_terminal(h, env):
    h.providers.sessions = [disc("claude:n1", "n1", status="idle", live=True, canResume=False, cwd=str(env["user"]))]
    s = h.client.get("/api/v1/sessions", headers=h.H).json()["sessions"][0]
    assert s["live"] is True and s["canResume"] is False
    assert h.client.post("/api/v1/sessions/claude:n1/resume", json={}, headers=h.H).status_code == 409
    assert h.terminal.calls == []


# ----------------------------------------------------------------------- SSE

class _Stream:
    """Drives the ASGI app directly so an endless SSE response can be read incrementally."""

    def __init__(self, app, token):
        self.app, self.token = app, token
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
            "scheme": "http", "path": "/api/v1/events", "raw_path": b"/api/v1/events", "query_string": b"",
            "root_path": "", "client": ("127.0.0.1", 5000), "server": ("localhost", 80),
            "headers": [(b"host", b"localhost"), (b"authorization", f"Bearer {self.token}".encode())],
        }
        self.task = asyncio.create_task(self.app(scope, receive, send))
        for _ in range(200):
            if self.task.done() or any("retry" in c for c in self.chunks):
                break
            await asyncio.sleep(0.01)
        return self

    @property
    def text(self):
        return "".join(self.chunks)

    async def ended(self, timeout=3.0):
        try:
            await asyncio.wait_for(asyncio.shield(self.task), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def close(self):
        self._gone.set()
        if self.task and not self.task.done():
            self.task.cancel()
            with contextlib.suppress(BaseException):
                await self.task


def test_revoked_device_stream_ends_before_next_event(h):
    other = h.pair(name="Tablet")

    async def run():
        mine = await _Stream(h.app, h.creds["token"]).open()
        theirs = await _Stream(h.app, other["token"]).open()
        assert mine.status == 200 and "event: update" in mine.text
        # Revoked from the Mac (admin CLI path: a different process, no in-app hook).
        assert h.app.state.devices.revoke(h.creds["deviceId"])
        h.app.state.bus.publish("notification", "m1", {"title": "Deploy prod", "body": "private details"})
        assert await mine.ended()
        await asyncio.sleep(0.1)
        assert "private details" not in mine.text
        assert "private details" in theirs.text  # still-paired devices keep receiving
        await mine.close(); await theirs.close()

    asyncio.run(run())


def test_unpair_ends_own_streams_immediately(h):
    async def run():
        s = await _Stream(h.app, h.creds["token"]).open()
        transport = httpx.ASGITransport(app=h.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://localhost") as c:
            r = await c.delete(f"/api/v1/devices/{h.creds['deviceId']}", headers=h.H)
        assert r.status_code == 200
        assert await s.ended(timeout=1.0)
        await s.close()

    asyncio.run(run())


def test_reconnecting_device_replaces_its_stale_streams(h):
    other = h.pair(name="Tablet")

    async def run():
        streams = []
        for _ in range(12):  # more than maxSseClients (8): e.g. network switches leaving half-open streams
            s = await _Stream(h.app, h.creds["token"]).open()
            assert s.status == 200
            streams.append(s)
        assert all([await s.ended(timeout=0.5) for s in streams[:-2]])
        assert not any([await s.ended(timeout=0.2) for s in streams[-2:]])
        t = await _Stream(h.app, other["token"]).open()
        assert t.status == 200  # another device is not locked out
        for s in [*streams, t]:
            await s.close()

    asyncio.run(run())
