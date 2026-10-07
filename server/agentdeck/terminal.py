"""Managed native TUI sessions in a private tmux server.

Design
------
* All managed agents run in a **dedicated tmux server** (``-S <state_dir>/tmux.sock``,
  own minimal config). The user's own tmux server and terminal panes are never
  queried or targeted.
* Each companion session is one tmux session named ``ad-<id>``. Its pane runs the
  real ``claude``/``codex`` executable directly (argv, no shell), carries the
  session option ``@agentdeck_id=<id>``, and exports ``AGENTDECK_SESSION_ID``.
  Every operation re-resolves the stored pane ID and verifies that tag first.
* The Mac terminal shows the same pane (``agentdeck run``/``attach`` = ``tmux attach``),
  so phone input goes into the same live TUI, not a cloned process.
* Input is only written when the TUI is observably ready (prompt box visible,
  no dialog, no spinner). Interrupt = a single ``Escape`` and only while the agent
  is working or a dialog blocks the prompt (never Ctrl-C, which exits idle Claude).
  Text is delivered via ``load-buffer`` + ``paste-buffer -p`` (bracketed paste)
  and submitted with one ``Enter``.
* Stop interrupts the current turn and keeps the conversation; panes are never killed
  by this module.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from agentdeck.errors import (
    ConflictError,
    DeliveryUncertainError,
    InvalidInputError,
    NotFoundError,
    NotReadyError,
    StaleApprovalError,
    UnavailableError,
)
from agentdeck.providers import claude as claude_provider
from agentdeck.providers import codex as codex_provider
from agentdeck.providers.common import PROCS, clip, descendants, redact, utc_iso
from agentdeck.providers.models import agent_binary
from agentdeck.providers.screen import Dialog, ScreenState, parse_screen

AGENTS = ("claude", "codex")
KEY_MAP = {"up": "Up", "down": "Down", "enter": "Enter", "escape": "Escape", "tab": "Tab"}
PREVIEW_LINES = 200
PREVIEW_MAX_CHARS = 24000
READY_TIMEOUT = 20.0
INTERRUPT_TIMEOUT = 15.0
DEAD_RETENTION_S = 7 * 86400
ENV_DENY = {"TMUX", "TMUX_PANE", "PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV", "AGENTDECK_SESSION_ID",
            "AGENTDECK_AGENT", "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CODEX_SANDBOX", "__CFBundleIdentifier"}
_SESSION_ID_RE = re.compile(r"^[a-z0-9]{6,32}$")
_UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

TMUX_CONF = """\
# Agent Deck private tmux server (only used for managed agent sessions)
set -g status off
set -s escape-time 10
set -g history-limit 20000
set -g focus-events on
set -g window-size latest
set -g aggressive-resize on
set -g remain-on-exit off
set -g destroy-unattached off
set -g exit-empty on
set -g set-titles on
set -g set-titles-string "#{session_name}"
"""


def tmux_binary() -> str | None:
    env = os.environ.get("AGENTDECK_TMUX_BIN")
    if env and os.access(env, os.X_OK):
        return env
    for cand in (shutil.which("tmux"), "/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux"):
        if cand and os.access(cand, os.X_OK):
            return cand
    return None


def default_state_dir() -> Path:
    from agentdeck.providers.common import agentdeck_home

    return agentdeck_home() / "terminal"


_login_env_cache: dict[str, str] | None = None


def login_environment() -> dict[str, str]:
    """Environment of the user's login shell (PATH etc.), used for sessions started from the phone.

    Runs ``$SHELL -l -c 'env -0'`` once (no interactive rc files). Falls back to os.environ.
    """
    global _login_env_cache
    if _login_env_cache is not None:
        return dict(_login_env_cache)
    shell = os.environ.get("SHELL") or "/bin/zsh"
    env = dict(os.environ)
    try:
        out = subprocess.run([shell, "-l", "-c", "env -0"], capture_output=True, timeout=8,
                             stdin=subprocess.DEVNULL, check=False).stdout
        parsed = {}
        for item in out.split(b"\0"):
            if b"=" in item:
                k, v = item.split(b"=", 1)
                parsed[k.decode(errors="replace")] = v.decode(errors="replace")
        if parsed.get("PATH"):
            env.update(parsed)
    except (OSError, subprocess.SubprocessError):
        pass
    _login_env_cache = env
    return dict(env)


class _Registry:
    """sessions.json (0600) with an inter-process file lock."""

    def __init__(self, state_dir: Path) -> None:
        self.path = state_dir / "sessions.json"
        self.lock_path = state_dir / "sessions.lock"
        self._tlock = threading.RLock()

    @contextmanager
    def locked(self) -> Iterator[dict]:
        with self._tlock:
            fd = os.open(self.lock_path, os.O_RDWR | os.O_CREAT, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
                data = self._read()
                before = json.dumps(data, sort_keys=True)
                yield data
                if json.dumps(data, sort_keys=True) != before:
                    self._write(data)
            finally:
                fcntl.flock(fd, fcntl.LOCK_UN)
                os.close(fd)

    def snapshot(self) -> dict:
        with self.locked() as data:
            return json.loads(json.dumps(data))

    def _read(self) -> dict:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"sessions": {}}
        if not isinstance(data, dict) or not isinstance(data.get("sessions"), dict):
            return {"sessions": {}}
        return data

    def _write(self, data: dict) -> None:
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=1)
        os.replace(tmp, self.path)


class TerminalManager:
    def __init__(self, state_dir: Path | str | None = None) -> None:
        self.state_dir = Path(state_dir) if state_dir else default_state_dir()
        self.state_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.state_dir, 0o700)
        self.tmux = tmux_binary()
        self.socket = self.state_dir / "tmux.sock"
        if len(str(self.socket)) > 100:
            raise UnavailableError("terminal state path too long for a tmux socket")
        self.conf = self.state_dir / "tmux.conf"
        if not self.conf.exists() or self.conf.read_text() != TMUX_CONF:
            fd = os.open(self.conf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as fh:
                fh.write(TMUX_CONF)
        self.registry = _Registry(self.state_dir)
        self._locks: dict[str, threading.RLock] = {}
        self._locks_guard = threading.Lock()
        self._first_seen: dict[str, float] = {}
        self._codex_assoc_at: dict[str, float] = {}
        self._pane_pids: dict[str, int] = {}  # "<tmux session>:<pane id>" -> pane pid

    # ------------------------------------------------------------------ tmux plumbing
    def _tmux(self, *args: str, input_bytes: bytes | None = None, check: bool = True,
              timeout: float = 10.0) -> subprocess.CompletedProcess:
        if not self.tmux:
            raise UnavailableError("tmux is not installed")
        cmd = [self.tmux, "-S", str(self.socket), "-f", str(self.conf), *args]
        env = {k: v for k, v in os.environ.items() if k not in ENV_DENY}
        try:
            res = subprocess.run(cmd, input=input_bytes, capture_output=True, timeout=timeout, env=env,
                                 stdin=None if input_bytes is not None else subprocess.DEVNULL)
        except subprocess.TimeoutExpired as exc:
            raise UnavailableError("tmux did not respond") from exc
        if check and res.returncode != 0:
            raise UnavailableError("tmux command failed: " + clip(res.stderr.decode(errors="replace").strip(), 160))
        return res

    def _lock(self, sid: str) -> threading.RLock:
        with self._locks_guard:
            return self._locks.setdefault(sid, threading.RLock())

    def _pane_info(self, pane_id: str) -> dict | None:
        fmt = "#{pane_id}\t#{session_name}\t#{@agentdeck_id}\t#{pane_dead}\t#{pane_pid}\t#{pane_current_command}"
        res = self._tmux("display-message", "-p", "-t", pane_id, fmt, check=False)
        if res.returncode != 0:
            return None
        parts = res.stdout.decode(errors="replace").rstrip("\n").split("\t")
        if len(parts) < 6:
            return None
        return {"paneId": parts[0], "session": parts[1], "tag": parts[2], "dead": parts[3] == "1",
                "pid": int(parts[4]) if parts[4].isdigit() else None, "command": parts[5]}

    def _live_panes(self) -> dict[str, dict]:
        """companion id -> pane info for every tagged pane on *our* server."""
        if not self.socket.exists():
            return {}
        fmt = "#{@agentdeck_id}\t#{pane_id}\t#{session_name}\t#{pane_dead}\t#{pane_pid}\t#{session_attached}"
        res = self._tmux("list-panes", "-a", "-F", fmt, check=False)
        if res.returncode != 0:
            return {}
        out = {}
        for line in res.stdout.decode(errors="replace").splitlines():
            p = line.split("\t")
            if len(p) >= 6 and p[0]:
                out[p[0]] = {"paneId": p[1], "session": p[2], "dead": p[3] == "1",
                             "pid": int(p[4]) if p[4].isdigit() else None, "attached": p[5] not in ("", "0")}
        return out

    def _resolve(self, sid: str) -> tuple[dict, dict]:
        if not isinstance(sid, str) or not _SESSION_ID_RE.match(sid):
            raise NotFoundError("unknown managed session")
        rec = self.registry.snapshot()["sessions"].get(sid)
        if not rec:
            raise NotFoundError("unknown managed session")
        info = self._pane_info(rec["paneId"]) if self.socket.exists() else None
        if not info or info["tag"] != sid or info["session"] != rec["tmuxSession"]:
            raise ConflictError("the managed terminal for this session has ended")
        if info["dead"]:
            raise ConflictError("the agent in this terminal has exited")
        return rec, info

    def _capture(self, pane_id: str, styled: bool = False, history: int = 0) -> str:
        args = ["capture-pane", "-p", "-t", pane_id]
        if styled:
            args.insert(1, "-e")
        else:
            args.insert(1, "-J")
        if history:
            args += ["-S", f"-{int(history)}"]
        return self._tmux(*args).stdout.decode("utf-8", errors="replace")

    def _observe(self, rec: dict, pane_id: str) -> ScreenState:
        plain = self._capture(pane_id)
        styled = self._capture(pane_id, styled=True) if rec["agent"] == "codex" else None
        st = parse_screen(rec["agent"], plain, styled)
        if rec["agent"] == "claude" and not st.working and st.dialog is None:
            # While Claude streams answer text no spinner is drawn; its own process registry
            # (~/.claude/sessions/<pid>.json status=busy) is the authoritative "turn running" signal.
            entry = self._claude_registry(rec, pane_id)
            if entry and entry.get("status") == "busy":
                st.working = True
                st.activity = st.activity or "Responding"
                st.notes.append("claude-registry-busy")
        return st

    def _claude_registry(self, rec: dict, pane_id: str) -> dict | None:
        key = f"{rec['tmuxSession']}:{pane_id}"
        pid = self._pane_pids.get(key)
        if pid is None:
            info = self._pane_info(pane_id)
            pid = info["pid"] if info else None
            if pid is None:
                return None
            self._pane_pids[key] = pid
        entry = claude_provider.session_for_pid(pid)
        if entry is None:
            for child in descendants(pid, PROCS.rows(1.0)):
                entry = claude_provider.session_for_pid(child["pid"])
                if entry:
                    break
        return entry

    def _keys(self, pane_id: str, *keys: str) -> None:
        for k in keys:
            self._tmux("send-keys", "-t", pane_id, k)

    def _wait(self, rec: dict, pane_id: str, pred, timeout: float, interval: float = 0.25) -> ScreenState:
        deadline = time.monotonic() + timeout
        st = self._observe(rec, pane_id)
        while not pred(st):
            if time.monotonic() >= deadline:
                raise NotReadyError("the terminal did not reach the expected state in time")
            time.sleep(interval)
            st = self._observe(rec, pane_id)
        return st

    # ------------------------------------------------------------------ session records
    def _new_id(self) -> str:
        return secrets.token_hex(6)

    def _argv(self, agent: str, model: str | None, prompt: str | None, resume: str | None,
              extra: list[str] | None, native_id: str | None) -> list[str]:
        binary = agent_binary(agent)
        if not binary:
            raise UnavailableError(f"{agent} CLI not found")
        argv = [binary]
        extra = list(extra or [])
        if agent == "claude":
            if resume:
                argv += ["--resume", resume]
            elif native_id:
                argv += ["--session-id", native_id]
            if model and model != "default":
                argv += ["--model", model]
            argv += extra
            if prompt:
                argv += ["--", prompt]
        else:
            if resume:
                argv += ["resume"]
            if model:
                argv += ["--model", model]
            argv += extra
            if resume:
                argv += [resume]
            if prompt:
                if not resume:
                    argv += ["--"]
                argv += [prompt]
        return argv

    def _spawn(self, agent: str, cwd: str, argv: list[str], *, env: dict[str, str], title: str | None,
               model: str | None, native_id: str | None, resume_of: str | None, origin: str) -> dict:
        if agent not in AGENTS:
            raise InvalidInputError("unknown agent")
        if not os.path.isdir(cwd):
            raise InvalidInputError("working directory does not exist")
        sid = self._new_id()
        name = f"ad-{sid}"
        env = {k: v for k, v in env.items() if k not in ENV_DENY and "\n" not in k and "=" not in k}
        env["AGENTDECK_SESSION_ID"] = sid
        env["AGENTDECK_AGENT"] = agent
        env_args: list[str] = []
        for k, v in env.items():
            env_args += ["-e", f"{k}={v}"]
        started = time.time()
        res = self._tmux("new-session", "-d", "-s", name, "-x", "160", "-y", "48", "-c", cwd,
                         *env_args, "-P", "-F", "#{pane_id}", "--", *argv)
        pane_id = res.stdout.decode().strip()
        if not re.match(r"^%\d+$", pane_id):
            raise UnavailableError("tmux did not return a pane id")
        self._tmux("set-option", "-t", name, "@agentdeck_id", sid)
        rec = {
            "id": sid, "agent": agent, "cwd": cwd, "model": model, "nativeId": native_id,
            "title": title, "tmuxSession": name, "paneId": pane_id, "createdAt": utc_iso(started),
            "startedAt": started, "resumeOf": resume_of, "origin": origin, "endedAt": None,
        }
        with self.registry.locked() as data:
            data["sessions"][sid] = rec
        return rec

    # ------------------------------------------------------------------ public API
    def start_session(self, agent: str, cwd: str, model: str | None = None, prompt: str | None = None, *,
                      resume_native_id: str | None = None, extra_args: list[str] | None = None,
                      origin: str = "api", env: dict[str, str] | None = None) -> dict:
        if resume_native_id:
            if agent not in AGENTS or not _UUID_RE.fullmatch(resume_native_id):
                raise InvalidInputError("invalid native session id")
            # The helper and a shell wrapper can resume simultaneously. Serialize the
            # existence check + spawn + registry write across both processes.
            lockpath = self.state_dir / f"resume-{agent}-{resume_native_id}.lock"
            with self._lock(f"resume:{agent}:{resume_native_id}"):
                fd = os.open(lockpath, os.O_CREAT | os.O_RDWR, 0o600)
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX)
                    return self._start_session(agent, cwd, model, prompt, resume_native_id=resume_native_id,
                                               extra_args=extra_args, origin=origin, env=env)
                finally:
                    fcntl.flock(fd, fcntl.LOCK_UN)
                    os.close(fd)
        return self._start_session(agent, cwd, model, prompt, extra_args=extra_args, origin=origin, env=env)

    def _start_session(self, agent: str, cwd: str, model: str | None = None, prompt: str | None = None, *,
                       resume_native_id: str | None = None, extra_args: list[str] | None = None,
                       origin: str = "api", env: dict[str, str] | None = None) -> dict:
        if agent not in AGENTS:
            raise InvalidInputError("agent must be claude or codex")
        if prompt is not None and not str(prompt).strip():
            prompt = None
        if resume_native_id is not None and not _UUID_RE.match(resume_native_id):
            raise InvalidInputError("invalid native session id")
        if resume_native_id:
            existing = self._managed_for_native(agent, resume_native_id)
            if existing:
                return existing
        native_id = None
        explicit = set(extra_args or [])
        if agent == "claude" and not resume_native_id and not explicit & {"--resume", "-r", "--continue", "-c",
                                                                          "--session-id", "--fork-session"}:
            native_id = str(uuid.uuid4())
        if resume_native_id:
            native_id = resume_native_id
        argv = self._argv(agent, model, prompt, resume_native_id, extra_args, native_id if not resume_native_id else None)
        if env is None:
            env = login_environment()
        title = None
        if resume_native_id:
            meta = (claude_provider.lookup(resume_native_id) if agent == "claude"
                    else codex_provider.lookup(resume_native_id)) or {}
            title = meta.get("title")
            model = model or meta.get("model")
        elif prompt:
            title = clip(" ".join(prompt.split()), 80)
        rec = self._spawn(agent, cwd, argv, env=env, title=title, model=model, native_id=native_id,
                          resume_of=resume_native_id, origin=origin)
        return self._session_dict(rec, self._live_panes().get(rec["id"]))

    def resume_session(self, session: dict) -> dict:
        """Resume a discovered (unmanaged) chat once inside a managed pane. Refuses duplicates."""
        agent = session.get("agent")
        native = session.get("nativeId")
        if agent not in AGENTS or not isinstance(native, str) or not _UUID_RE.match(native):
            raise InvalidInputError("this chat cannot be resumed")
        existing = self._managed_for_native(agent, native)
        if existing:
            return existing
        if agent == "claude":
            live = claude_provider.live_registry().get(native)
            if live:
                raise ConflictError("this Claude chat is still open in another terminal; exit it there first")
            info = claude_provider.lookup(native)
        else:
            from agentdeck.providers import codex_daemon

            loaded = codex_daemon.loaded_threads(max_age=0) or {}
            if native in loaded:
                raise ConflictError("this Codex chat is still open in another terminal; exit it there first")
            if native in codex_provider.live_processes(max_age=0)["byThread"]:
                raise ConflictError("this Codex chat is still open in another terminal; exit it there first")
            info = codex_provider.lookup(native)
        if not info:
            raise NotFoundError("native chat not found")
        cwd = session.get("cwd") or info.get("cwd")
        if not cwd or not os.path.isdir(cwd):
            raise InvalidInputError("the chat's working directory no longer exists")
        return self.start_session(agent, cwd, None, None, resume_native_id=native, origin="resume")

    def list_sessions(self) -> list[dict]:
        panes = self._live_panes()
        now = time.time()
        out = []
        with self.registry.locked() as data:
            sessions = data["sessions"]
            for sid in list(sessions):
                rec = sessions[sid]
                pane = panes.get(sid)
                alive = bool(pane and not pane["dead"] and pane["paneId"] == rec["paneId"])
                if not alive and not rec.get("endedAt"):
                    rec["endedAt"] = utc_iso(now)
                if not alive and rec.get("endedAt"):
                    from agentdeck.providers.common import parse_iso

                    ended = parse_iso(rec["endedAt"]) or now
                    if now - ended > DEAD_RETENTION_S:
                        del sessions[sid]
                        continue
        snapshot = self.registry.snapshot()["sessions"]
        for sid, rec in snapshot.items():
            pane = panes.get(sid)
            try:
                if pane and not pane["dead"]:
                    self._associate(rec, pane)
                out.append(self._session_dict(self.registry.snapshot()["sessions"].get(sid, rec), pane))
            except Exception:  # noqa: BLE001 - one broken pane must not hide others
                out.append(self._session_dict(rec, None))
        return out

    def get_session(self, sid: str) -> dict:
        rec = self.registry.snapshot()["sessions"].get(sid)
        if not rec:
            raise NotFoundError("unknown managed session")
        pane = self._live_panes().get(sid)
        if pane and not pane["dead"]:
            self._associate(rec, pane)
            rec = self.registry.snapshot()["sessions"].get(sid, rec)
        return self._session_dict(rec, pane)

    def send(self, session_id: str, text: str, interrupt: bool = True) -> None:
        if not isinstance(text, str) or not text.strip():
            raise InvalidInputError("message is empty")
        if any(ord(c) < 32 and c not in "\n\t" for c in text) or "\x7f" in text:
            raise InvalidInputError("message contains control characters")
        with self._lock(session_id):
            rec, info = self._resolve(session_id)
            pane = info["paneId"]
            st = self._observe(rec, pane)
            interrupted = False
            if st.dialog is not None:
                if not interrupt:
                    raise NotReadyError("the agent is waiting for a choice; answer it or send with interrupt")
                self._keys(pane, "Escape")
                interrupted = True
                st = self._wait(rec, pane, lambda s: s.dialog is None, INTERRUPT_TIMEOUT)
            if st.working and interrupt:
                self._keys(pane, "Escape")
                interrupted = True
                st = self._wait(rec, pane, lambda s: not s.working or s.dialog is not None, INTERRUPT_TIMEOUT)
                if st.dialog is not None:
                    raise NotReadyError("a new prompt appeared after interrupting; check the session")
            if st.working and not interrupt:
                # native queueing: both TUIs accept a submitted message while a turn runs
                st = self._wait(rec, pane, lambda s: s.input_visible and s.dialog is None, READY_TIMEOUT)
            else:
                st = self._wait(rec, pane, lambda s: s.ready, READY_TIMEOUT)
            if st.draft:
                self._clear_draft(rec, pane, st.draft, restored=interrupted)
            self._paste(rec, pane, text)
            try:
                self._keys(pane, "Enter")
                self._wait_submitted(rec, pane)
            except Exception:
                raise DeliveryUncertainError("The reply may have been delivered, but the terminal did not confirm it. Check the chat before sending another message.") from None

    def stop(self, session_id: str) -> None:
        with self._lock(session_id):
            rec, info = self._resolve(session_id)
            pane = info["paneId"]
            st = self._observe(rec, pane)
            if not st.working and st.dialog is None:
                return  # nothing running: never send keys to an idle TUI
            self._keys(pane, "Escape")
            self._wait(rec, pane, lambda s: not s.working and s.dialog is None, INTERRUPT_TIMEOUT)

    def preview(self, session_id: str) -> str:
        rec, info = self._resolve(session_id)
        text = self._capture(info["paneId"], history=PREVIEW_LINES)
        lines = [ln.rstrip() for ln in text.split("\n")]
        while lines and not lines[-1]:
            lines.pop()
        out = redact("\n".join(lines))
        return out[-PREVIEW_MAX_CHARS:]

    def approvals(self, session_id: str) -> list[dict]:
        rec, info = self._resolve(session_id)
        st = self._observe(rec, info["paneId"])
        if st.dialog is None:
            return []
        d = st.dialog
        key = f"{session_id}:{d.fingerprint}"
        first = self._first_seen.setdefault(key, time.time())
        if len(self._first_seen) > 256:
            self._first_seen = {key: first}
        return [{
            "id": d.approval_id,
            "sessionId": session_id,
            "title": clip(redact(d.title), 300),
            "detail": clip(redact(d.detail), 4000),
            "choices": [{"id": d.choice_id(o), "label": clip(redact(o.label), 300)} for o in d.options],
            "createdAt": utc_iso(first),
            "fingerprint": d.fingerprint,
        }]

    def respond_approval(self, session_id: str, approval_id: str, choice_id: str) -> None:
        with self._lock(session_id):
            rec, info = self._resolve(session_id)
            pane = info["paneId"]
            st = self._observe(rec, pane)
            d = st.dialog
            if d is None or d.approval_id != approval_id:
                raise StaleApprovalError("this prompt is no longer shown in the terminal")
            target = next((o for o in d.options if d.choice_id(o) == str(choice_id)), None)
            if target is None:
                raise StaleApprovalError("that choice is not offered by the current prompt")
            if target.number is not None and 1 <= target.number <= 9:
                self._keys(pane, str(target.number))
            else:
                self._select_by_cursor(rec, pane, d, target)
            # confirm the prompt actually went away (or changed)
            try:
                self._wait(rec, pane, lambda s: s.dialog is None or s.dialog.fingerprint != d.fingerprint, 5.0)
            except NotReadyError:
                raise NotReadyError("the terminal did not accept the choice") from None
            except (UnavailableError, ConflictError):
                # The choice was delivered and the agent exited because of it (e.g. "No, exit"):
                # the pane is gone or dead, which still means the prompt was answered.
                after = self._pane_info(pane) if self.socket.exists() else None
                if after is None or after["dead"]:
                    return
                raise

    def input_key(self, session_id: str, key: str) -> None:
        tk = KEY_MAP.get(key)
        if not tk:
            raise InvalidInputError("unsupported key")
        with self._lock(session_id):
            _, info = self._resolve(session_id)
            self._keys(info["paneId"], tk)

    def attach_command(self, session_id: str) -> list[str]:
        rec, _ = self._resolve(session_id)
        return [self.tmux or "tmux", "-S", str(self.socket), "-f", str(self.conf), "attach-session", "-t", rec["tmuxSession"]]

    def find_live(self, agent: str, native_id: str) -> dict | None:
        return self._managed_for_native(agent, native_id)

    def close(self) -> None:
        """Server shutdown hook: tmux sessions intentionally outlive the helper."""
        return None

    # ------------------------------------------------------------------ internals
    def _managed_for_native(self, agent: str, native_id: str) -> dict | None:
        panes = self._live_panes()
        for sid, rec in self.registry.snapshot()["sessions"].items():
            pane = panes.get(sid)
            if rec["agent"] == agent and rec.get("nativeId") == native_id and pane and not pane["dead"]:
                return self._session_dict(rec, pane)
        return None

    def _associate(self, rec: dict, pane: dict) -> None:
        """Keep nativeId current from real process evidence (handles /clear, /new, resume pickers)."""
        pid = pane.get("pid")
        if not pid:
            return
        new_native = None
        if rec["agent"] == "claude":
            cands = [pid] + [p["pid"] for p in descendants(pid, PROCS.rows(1.0))]
            for cpid in cands:
                entry = claude_provider.session_for_pid(cpid)
                if entry and isinstance(entry.get("sessionId"), str):
                    new_native = entry["sessionId"]
                    break
        else:
            from agentdeck.providers import codex_daemon

            last = self._codex_assoc_at.get(rec["id"], 0)
            if rec.get("nativeId") and time.monotonic() - last < 20:
                return
            self._codex_assoc_at[rec["id"]] = time.monotonic()
            cands = [pid] + [p["pid"] for p in descendants(pid, PROCS.rows(1.0))]
            new_native = codex_provider.thread_for_pids(cands)
            # A cwd/time match is not proof of ownership: another terminal may have
            # opened the thread. Prefer an unknown identity over somebody else's chat.
        if new_native and new_native != rec.get("nativeId"):
            with self.registry.locked() as data:
                r = data["sessions"].get(rec["id"])
                if r:
                    r["nativeId"] = new_native
                    if r.get("title") is None or r.get("resumeOf") not in (None, new_native):
                        r["title"] = None
            rec["nativeId"] = new_native

    def _session_dict(self, rec: dict, pane: dict | None) -> dict:
        alive = bool(pane and not pane["dead"] and pane["paneId"] == rec["paneId"])
        status, stage = "offline", "Terminal closed"
        if alive:
            try:
                st = self._observe(rec, rec["paneId"])
                status = st.status
                stage = st.activity if st.working else (st.dialog.title if st.dialog else None)
            except Exception:  # noqa: BLE001
                status, stage = "unknown", None
        meta = None
        if rec.get("nativeId"):
            try:
                meta = (claude_provider.lookup(rec["nativeId"]) if rec["agent"] == "claude"
                        else codex_provider.lookup(rec["nativeId"]))
            except Exception:  # noqa: BLE001
                meta = None
        meta = meta or {}
        title = meta.get("title") or rec.get("title") or os.path.basename(rec["cwd"]) or rec["agent"]
        return {
            "id": rec["id"],
            "agent": rec["agent"],
            "nativeId": rec.get("nativeId"),
            "title": title,
            "cwd": rec["cwd"],
            "model": meta.get("model") or rec.get("model"),
            "status": status,
            "stage": stage,
            "progress": None,
            "updatedAt": utc_iso(),
            "managed": True,
            "capabilities": {"send": alive, "interrupt": alive, "approve": alive, "stop": alive},
            "lastMessage": meta.get("lastMessage"),
            "unread": 0,
            "attached": bool(pane and pane.get("attached")),
            "tmuxSession": rec["tmuxSession"],
            "statusEvidence": "managed-tmux-screen" if alive else "managed-registry",
            "_transcriptPath": meta.get("path") if rec["agent"] == "claude" else None,
            "_rolloutPath": meta.get("path") if rec["agent"] == "codex" else None,
        }

    def _paste(self, rec: dict, pane: str, text: str) -> None:
        buf = f"agentdeck-{secrets.token_hex(4)}"
        self._tmux("load-buffer", "-b", buf, "-", input_bytes=text.encode("utf-8"))
        # -p: bracketed paste when the app asked for it; -r: keep LF (no CR translation); -d: delete buffer
        self._tmux("paste-buffer", "-p", "-r", "-d", "-b", buf, "-t", pane)
        first = next((ln.strip() for ln in text.split("\n") if ln.strip()), "")[:20]
        try:
            self._wait(rec, pane, lambda s: bool(s.draft) and (not first or first in s.draft or "Pasted text" in s.draft
                                                               or "[Pasted" in s.draft), 4.0, 0.15)
        except NotReadyError:
            raise NotReadyError("the pasted text did not appear in the prompt; nothing was submitted") from None

    def _wait_submitted(self, rec: dict, pane: str) -> None:
        try:
            self._wait(rec, pane, lambda s: s.working or s.dialog is not None or not s.draft, 6.0, 0.2)
        except NotReadyError:
            raise NotReadyError("message pasted but the terminal did not confirm submission") from None

    def _clear_draft(self, rec: dict, pane: str, draft: str, restored: bool) -> None:
        if not restored:
            self._save_cleared_draft(rec, draft)
        for _ in range(80):
            self._keys(pane, "C-u")
            st = self._observe(rec, pane)
            if not st.draft:
                return
            self._keys(pane, "BSpace")
            st = self._observe(rec, pane)
            if not st.draft:
                return
        raise NotReadyError("could not clear unsent text in the terminal prompt")

    def _save_cleared_draft(self, rec: dict, draft: str) -> None:
        path = self.state_dir / "cleared-drafts.jsonl"
        try:
            lines = path.read_text(encoding="utf-8").splitlines()[-19:] if path.exists() else []
        except OSError:
            lines = []
        lines.append(json.dumps({"sessionId": rec["id"], "at": utc_iso(), "text": draft[:8000]}))
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

    def _select_by_cursor(self, rec: dict, pane: str, d: Dialog, target) -> None:
        for _ in range(len(d.options) + 2):
            st = self._observe(rec, pane)
            cur = st.dialog
            if cur is None or cur.fingerprint != d.fingerprint:
                raise StaleApprovalError("the prompt changed while selecting")
            if cur.cursor is None:
                raise NotReadyError("cannot see the selection cursor")
            if cur.cursor == target.index:
                self._keys(pane, "Enter")
                return
            self._keys(pane, "Down" if cur.cursor < target.index else "Up")
            time.sleep(0.15)
        raise NotReadyError("could not move the selection to that choice")
