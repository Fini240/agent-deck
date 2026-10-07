from __future__ import annotations

import copy
import threading
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from agentdeck.errors import StaleApprovalError
from agentdeck.push import PushStatus


class Clock:
    def __init__(self, t: float = 1_800_000_000.0):
        self.t = t

    def __call__(self) -> float:
        return self.t

    def advance(self, seconds: float) -> None:
        self.t += seconds


class FakeProviders:
    def __init__(self):
        self.sessions: list[dict] = []
        self.messages: dict[str, list[dict]] = {}
        self.model_calls: list[bool] = []

    def discover_sessions(self):
        return copy.deepcopy(self.sessions)

    def read_messages(self, session, limit=100):
        return self.messages.get(session.get("nativeId") or session["id"], [])[-limit:]

    def discover_models(self, refresh=False):
        self.model_calls.append(refresh)
        return {
            "agents": [
                {"id": "claude", "name": "Claude Code", "available": True, "version": "x", "models": [
                    {"id": "live-model-1", "label": "Live 1", "description": None, "source": "live"}],
                 "modelSource": "live", "modelRefreshedAt": "2026-10-07T00:00:00Z", "error": None}
            ],
            "refreshedAt": "2026-10-07T00:00:00Z",
        }


class FakeTerminal:
    def __init__(self):
        self.sessions: dict[str, dict] = {}
        self.calls: list[tuple] = []
        self.approval_map: dict[str, list[dict]] = {}
        self.preview_text = ""
        self.lock = threading.Lock()

    def add(self, sid: str, **kw) -> dict:
        s = {
            "id": sid, "agent": "claude", "nativeId": None, "title": sid, "cwd": "/tmp", "model": None,
            "status": "idle", "stage": None, "progress": None, "updatedAt": "2026-10-07T00:00:00.000Z",
            "capabilities": {"send": True, "interrupt": True, "approve": True, "stop": True},
            "lastMessage": None, "unread": 0,
        }
        s.update(kw)
        self.sessions[sid] = s
        return s

    def list_sessions(self):
        return copy.deepcopy(list(self.sessions.values()))

    def start_session(self, agent, cwd, model=None, prompt=None):
        self.calls.append(("start", agent, cwd, model, prompt))
        return self.add(f"m{len(self.sessions) + 1}", agent=agent, cwd=cwd, model=model, status="working")

    def _check(self, sid):
        if sid not in self.sessions:
            raise KeyError(sid)

    def send(self, session_id, text, interrupt=True):
        self._check(session_id)
        self.calls.append(("send", session_id, text, interrupt))

    def stop(self, session_id):
        self._check(session_id)
        self.calls.append(("stop", session_id))

    def preview(self, session_id):
        self._check(session_id)
        return self.preview_text

    def approvals(self, session_id):
        self._check(session_id)
        return copy.deepcopy(self.approval_map.get(session_id, []))

    def respond_approval(self, session_id, approval_id, choice_id):
        if not any(a["id"] == approval_id for a in self.approval_map.get(session_id, [])):
            raise StaleApprovalError("gone")
        self.calls.append(("approve", session_id, approval_id, choice_id))
        self.approval_map[session_id] = []

    def input_key(self, session_id, key):
        self._check(session_id)
        self.calls.append(("input", session_id, key))

    def resume_session(self, session):
        self.calls.append(("resume", session["id"]))
        return self.add("m-resumed", agent=session["agent"], nativeId=session["nativeId"], cwd=session.get("cwd"))


class FakeSender:
    def __init__(self, available: bool = True, outcome: str = "ok"):
        self.available = available
        self.outcome = outcome
        self.sent: list[tuple[str, dict]] = []

    def status(self):
        return PushStatus(self.available, None if self.available else "not configured", "test-project" if self.available else None)

    def send(self, fcm_token, message):
        self.sent.append((fcm_token, message))
        return self.outcome if self.available else "unavailable"


class FakeKeepAwake:
    def __init__(self):
        self.held = False
        self.mode = None
        self.history: list[tuple[bool, str]] = []

    def update(self, active, mode):
        self.history.append((active, mode))
        self.held = bool(active and mode != "off")
        self.mode = mode if self.held else None

    def release(self):
        self.held = False
        self.history.append((False, "release"))

    def status(self):
        return {"held": self.held, "mode": self.mode, "error": None}


@pytest.fixture
def env(tmp_path, monkeypatch):
    home = tmp_path / "deckhome"
    user = tmp_path / "user"
    (user / "projects" / "app").mkdir(parents=True)
    monkeypatch.setenv("AGENTDECK_HOME", str(home))
    monkeypatch.setenv("AGENTDECK_USER_HOME", str(user))
    monkeypatch.delenv("AGENTDECK_PORT", raising=False)
    return {"home": home, "user": user.resolve(), "tmp": tmp_path}


class Harness:
    def __init__(self, env, **overrides):
        from agentdeck.app import create_app

        self.env = env
        self.clock = overrides.pop("clock", Clock())
        self.providers = overrides.pop("providers", FakeProviders())
        self.terminal = overrides.pop("terminal", FakeTerminal())
        self.sender = overrides.pop("sender", FakeSender())
        self.keepawake = overrides.pop("keepawake", FakeKeepAwake())
        self.app = create_app(
            providers=self.providers,
            terminal=self.terminal,
            push_sender=self.sender,
            keepawake=self.keepawake,
            clock=self.clock,
            start_monitor=False,
            async_push=False,
            detect_tailscale=False,
            **overrides,
        )
        self.hub = self.app.state.hub
        self.client = TestClient(self.app, base_url="http://localhost")
        self.client.__enter__()

    def pair(self, name="Fold", fcm_token="f" * 40) -> dict:
        code, _ = self.app.state.devices.create_pairing_code()
        r = self.client.post("/api/v1/pair", json={"code": code, "deviceName": name, "fcmToken": fcm_token})
        assert r.status_code == 200, r.text
        return r.json()

    def auth(self, creds: dict) -> dict:
        return {"Authorization": f"Bearer {creds['token']}"}

    def close(self):
        self.client.__exit__(None, None, None)


@pytest.fixture
def harness(env):
    h = Harness(env)
    yield h
    h.close()


@pytest.fixture
def make_harness(env):
    made = []

    def factory(**kw):
        h = Harness(env, **kw)
        made.append(h)
        return h

    yield factory
    for h in made:
        h.close()


FIXTURES = Path(__file__).parent / "fixtures"
