"""Session state hub: merges provider discovery with managed terminals, tracks
status transitions, decides notifications and drives the keep-awake assertion.

Everything here is synchronous and clock-injectable so it can be tested without
an event loop; ``app.py`` runs ``poll_once`` in a worker thread.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import threading
import time
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable

from .auth import utc_iso
from .errors import NotFoundError
from .security import redact

log = logging.getLogger("agentdeck.monitor")

STATUSES = ("working", "idle", "needs_input", "completed", "error", "unknown", "offline")
DEFINITE = {"working", "idle", "needs_input", "completed", "error"}
SESSION_KEYS = (
    "id", "agent", "nativeId", "title", "cwd", "model", "status", "stage", "progress",
    "updatedAt", "managed", "capabilities", "lastMessage", "unread",
)
# Optional, additive fields (old clients ignore them).
OPTIONAL_KEYS = (
    "parentSessionId", "agentName", "agentRole", "isSubagent", "task", "projectRoot",
    "statusEvidence", "live", "canResume",
)
NO_CAPS = {"send": False, "interrupt": False, "approve": False, "stop": False}


# ------------------------------------------------------------------ helpers

def clean_progress(value: Any) -> dict[str, Any] | None:
    """Return a valid Progress or None. Never invent a total."""
    if not isinstance(value, dict):
        return None
    cur, tot = value.get("current"), value.get("total")
    if isinstance(cur, bool) or isinstance(tot, bool):
        return None
    if not isinstance(cur, (int, float)) or not isinstance(tot, (int, float)):
        return None
    if not math.isfinite(cur) or not math.isfinite(tot) or tot <= 0 or cur < 0 or cur > tot:
        return None
    unit = value.get("unit")
    return {"current": cur, "total": tot, "unit": unit if isinstance(unit, str) and unit else None}


def normalize_session(raw: dict[str, Any], managed: bool) -> dict[str, Any]:
    s = dict(raw)
    s["managed"] = bool(managed)
    status = s.get("status")
    s["status"] = status if status in STATUSES else "unknown"
    if s.get("agent") not in ("claude", "codex"):
        s["agent"] = str(s.get("agent") or "unknown")
    s["nativeId"] = s.get("nativeId") or None
    s["title"] = str(s.get("title") or s.get("nativeId") or s.get("id"))[:300]
    s["cwd"] = s.get("cwd") or None
    s["model"] = s.get("model") or None
    s["stage"] = s.get("stage") or None
    s["progress"] = clean_progress(s.get("progress"))
    s["updatedAt"] = s.get("updatedAt") or utc_iso()
    last = s.get("lastMessage")
    s["lastMessage"] = redact(str(last))[:2000] if last else None
    unread = s.get("unread")
    s["unread"] = unread if isinstance(unread, int) and unread >= 0 else 0
    s["isSubagent"] = bool(s.get("isSubagent"))
    for key, limit in (("parentSessionId", 200), ("agentName", 200), ("agentRole", 120),
                       ("projectRoot", 1024), ("statusEvidence", 120)):
        v = s.get(key)
        s[key] = str(v)[:limit] if v else None
    task = s.get("task")
    s["task"] = redact(str(task))[:2000] if task else None
    s["live"] = bool(s.get("live")) if s.get("live") is not None else None
    if managed or s["isSubagent"]:
        s["canResume"] = False
    elif s.get("canResume") is not None:
        s["canResume"] = bool(s["canResume"]) and not s["live"]
    else:
        s["canResume"] = not s["live"] and s["status"] not in ("working", "needs_input")
    caps = s.get("capabilities") if isinstance(s.get("capabilities"), dict) else {}
    if managed:
        s["capabilities"] = {k: bool(caps.get(k, True)) for k in NO_CAPS}
    else:
        s["capabilities"] = dict(NO_CAPS)
    return s


def merge_sessions(discovered: list[dict], managed: list[dict]) -> tuple[list[dict], dict[str, str]]:
    """Managed entries win; discovered duplicates fold into them and become aliases."""
    by_key: dict[tuple, dict] = {}
    result: dict[str, dict] = {}
    aliases: dict[str, str] = {}
    for m in managed:
        if not isinstance(m, dict) or not m.get("id"):
            continue
        entry = dict(m)
        result[entry["id"]] = entry
        if entry.get("nativeId"):
            by_key[(entry.get("agent"), entry["nativeId"])] = entry
    for d in discovered:
        if not isinstance(d, dict) or not d.get("id"):
            continue
        key = (d.get("agent"), d.get("nativeId"))
        # Subagents keep their own identity and are never folded into a managed parent.
        target = by_key.get(key) if d.get("nativeId") and not d.get("isSubagent") else None
        if target is not None:
            merged = dict(d)
            for k, v in target.items():
                if v is None:
                    continue
                if k == "status" and v == "unknown" and d.get("status") in DEFINITE:
                    continue
                merged[k] = v
            if d.get("updatedAt") and target.get("updatedAt"):
                merged["updatedAt"] = max(d["updatedAt"], target["updatedAt"])
            merged["id"] = target["id"]
            result[target["id"]] = merged
            by_key[key] = merged
            if d["id"] != target["id"]:
                aliases[d["id"]] = target["id"]
        elif d["id"] in result:
            continue  # never let discovery shadow a managed id
        else:
            result[d["id"]] = dict(d)
    managed_ids = {m["id"] for m in managed if isinstance(m, dict) and m.get("id")}
    native_to_id = {(s.get("agent"), s.get("nativeId")): sid for sid, s in result.items()
                    if s.get("nativeId") and not s.get("isSubagent")}
    for s in result.values():
        parent = s.get("parentSessionId")
        if not parent:
            continue
        parent = aliases.get(parent, parent)
        if parent not in result:  # provider may reference the parent's native id
            parent = native_to_id.get((s.get("agent"), parent), parent)
        s["parentSessionId"] = parent
    out = [normalize_session(s, s["id"] in managed_ids) for s in result.values()]
    out.sort(key=lambda s: s["updatedAt"], reverse=True)
    return out, aliases


def public_session(s: dict[str, Any]) -> dict[str, Any]:
    out = {k: s.get(k) for k in SESSION_KEYS}
    for k in OPTIONAL_KEYS:
        out[k] = s.get(k, False if k in ("isSubagent", "canResume") else None)
    return out


# ---------------------------------------------------------- status machine

@dataclass
class _Track:
    confirmed: str
    last_definite: str | None
    pending: str | None = None
    pending_since: float = 0.0
    missing_polls: int = 0


class StatusTracker:
    """Debounced status machine producing alert kinds.

    * ``working -> idle|completed`` alerts ``completed`` only after the new state
      held for ``debounce`` seconds (TUIs flicker idle between tool calls).
    * ``-> error`` alerts ``error``; ``-> needs_input`` alerts ``input`` (immediate).
    * ``unknown``/``offline`` never alert and never erase the last definite state,
      so ``working -> unknown -> idle`` still completes but a quiet transcript alone
      never claims completion.
    * The first observation of a session is a baseline and never alerts.
    """

    def __init__(self, debounce: float = 6.0):
        self.debounce = debounce
        self.tracks: dict[str, _Track] = {}

    def is_working(self, sid: str) -> bool:
        t = self.tracks.get(sid)
        return bool(t and t.confirmed == "working")

    def observe(self, sid: str, status: str, now: float) -> str | None:
        t = self.tracks.get(sid)
        if t is None:
            self.tracks[sid] = _Track(status, status if status in DEFINITE else None)
            return None
        t.missing_polls = 0
        if status == t.confirmed:
            t.pending = None
            return None
        if status in ("idle", "completed") and t.last_definite == "working":
            if t.pending != status:
                t.pending, t.pending_since = status, now
                return None
            if now - t.pending_since < self.debounce:
                return None
        return self._confirm(t, status)

    def _confirm(self, t: _Track, status: str) -> str | None:
        prev_definite = t.last_definite
        t.confirmed, t.pending = status, None
        if status not in DEFINITE:
            return None
        t.last_definite = status
        if status == prev_definite:
            return None
        if status in ("idle", "completed") and prev_definite == "working":
            return "completed"
        if status == "error":
            return "error"
        if status == "needs_input":
            return "input"
        return None

    def forget_missing(self, present: set[str], max_missing: int = 20) -> None:
        for sid in list(self.tracks):
            if sid in present:
                continue
            t = self.tracks[sid]
            t.missing_polls += 1
            if t.missing_polls > max_missing:
                del self.tracks[sid]


# --------------------------------------------------------------------- hub

@dataclass
class _NotifyLease:
    kind: str
    at: float
    stage: str | None
    progress: dict | None


@dataclass
class HubState:
    sessions: list[dict] = field(default_factory=list)
    by_id: dict[str, dict] = field(default_factory=dict)
    aliases: dict[str, str] = field(default_factory=dict)
    refreshed_at: float = 0.0
    error: str | None = None


class SessionHub:
    def __init__(
        self,
        *,
        providers: Any | None,
        terminal: Any | None,
        get_settings: Callable[[], dict],
        push: Any | None,
        bus: Any | None,
        keepawake: Any | None,
        debounce: float = 6.0,
        lease_seconds: float = 600.0,
        clock: Callable[[], float] = time.time,
        async_push: bool = True,
        providers_error: str | None = None,
    ):
        self.providers = providers
        self.terminal = terminal
        self.get_settings = get_settings
        self.push = push
        self.bus = bus
        self.keepawake = keepawake
        self.lease_seconds = lease_seconds
        self.clock = clock
        self.providers_error = providers_error
        self.tracker = StatusTracker(debounce)
        self.state = HubState()
        self._refresh_lock = threading.Lock()
        # Guards tracker/lease/alert/progress state shared by the poll thread and /notify requests.
        self._state_lock = threading.RLock()
        self._session_locks: dict[str, threading.Lock] = {}
        self._locks_guard = threading.Lock()
        self._leases: dict[str, _NotifyLease] = {}
        self._last_alert: dict[tuple, float] = {}
        self._last_progress_push: dict[str, float] = {}
        self._pending_progress: dict[str, dict] = {}
        self._last_progress_sig: dict[str, str] = {}
        self._known_approvals: dict[str, set[str]] = {}
        self._last_seen_sig: dict[str, str] = {}
        self._list_hash = ""
        self._baseline_done = False
        self.notifications: deque[dict] = deque(maxlen=200)
        self._push_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="push") if async_push else None
        self._models_cache: dict | None = None
        self._models_lock = threading.Lock()

    # ------------------------------------------------------------ locking
    def session_lock(self, sid: str) -> threading.Lock:
        with self._locks_guard:
            return self._session_locks.setdefault(sid, threading.Lock())

    # ---------------------------------------------------------- discovery
    def _discover(self) -> tuple[list[dict], list[dict], str | None]:
        errors = []
        discovered: list[dict] = []
        managed: list[dict] = []
        if self.providers is not None:
            try:
                discovered = list(self.providers.discover_sessions() or [])
            except Exception as exc:
                log.exception("discover_sessions failed")
                errors.append(f"discovery failed ({type(exc).__name__})")
        elif self.providers_error:
            errors.append(self.providers_error)
        if self.terminal is not None:
            try:
                managed = list(self.terminal.list_sessions() or [])
            except Exception as exc:
                log.exception("list_sessions failed")
                errors.append(f"terminal listing failed ({type(exc).__name__})")
        return discovered, managed, "; ".join(errors) or None

    def refresh(self) -> HubState:
        with self._refresh_lock:
            discovered, managed, error = self._discover()
            sessions, aliases = merge_sessions(discovered, managed)
            now = self.clock()
            self._apply_leases(sessions, now)
            self.state = HubState(sessions, {s["id"]: s for s in sessions}, aliases, now, error)
            return self.state

    def snapshot(self, max_age: float = 2.0) -> HubState:
        if max_age <= 0 or self.clock() - self.state.refreshed_at > max_age:
            return self.refresh()
        return self.state

    def resolve(self, sid: str, max_age: float = 2.0) -> dict[str, Any]:
        st = self.snapshot(max_age)
        real = st.aliases.get(sid, sid)
        s = st.by_id.get(real)
        if s is None:
            # Maybe a native id (from agent notify hooks) or a brand-new session.
            for cand in st.sessions:
                if cand.get("nativeId") == sid:
                    return cand
            st = self.refresh()
            real = st.aliases.get(sid, sid)
            s = st.by_id.get(real)
        if s is None:
            raise NotFoundError("session not found")
        return s

    def try_resolve(self, sid: str) -> dict[str, Any] | None:
        try:
            return self.resolve(sid, max_age=30)
        except NotFoundError:
            return None

    # -------------------------------------------------------------- models
    def models(self, refresh: bool = False) -> dict[str, Any]:
        with self._models_lock:
            if self._models_cache is not None and not refresh:
                return self._models_cache
            if self.providers is None:
                return {"agents": [], "refreshedAt": utc_iso(self.clock()), "error": self.providers_error or "providers unavailable"}
            result = self.providers.discover_models(refresh=refresh)
            self._models_cache = result
        if refresh and self.bus:
            self.bus.publish("models")
        return result

    # ------------------------------------------------------- notifications
    def _apply_leases(self, sessions: list[dict], now: float) -> None:
        for s in sessions:
            lease = self._leases.get(s["id"]) or (self._leases.get(s["nativeId"]) if s.get("nativeId") else None)
            if not lease or lease.kind != "progress" or now - lease.at > self.lease_seconds:
                continue
            if lease.stage and not s.get("stage"):
                s["stage"] = lease.stage
            if lease.progress and not s.get("progress"):
                s["progress"] = lease.progress

    def notify_active(self, now: float | None = None) -> bool:
        now = self.clock() if now is None else now
        with self._state_lock:
            return any(l.kind == "progress" and now - l.at <= self.lease_seconds for l in self._leases.values())

    def _progress_lease_active(self, sid: str, now: float) -> bool:
        lease = self._leases.get(sid)
        return bool(lease and lease.kind == "progress" and now - lease.at <= self.lease_seconds)

    def _payload(self, kind: str, session: dict | None, sid: str, title: str, body: str,
                 progress: dict | None, stage: str | None) -> dict[str, Any]:
        return {
            "eventId": uuid.uuid4().hex,
            "type": kind,
            "sessionId": session["id"] if session else sid,
            "title": redact(title)[:200],
            "body": redact(body)[:1000],
            "agent": session.get("agent", "unknown") if session else "unknown",
            "timestamp": utc_iso(self.clock()),
            "progress": progress,
            "stage": (stage or None) and redact(stage)[:200],
            "canReply": bool(session and session.get("managed") and not session.get("isSubagent")
                             and session.get("capabilities", {}).get("send") and kind != "input"),
            "validForSeconds": max(180, int(self.get_settings().get("progressIntervalSeconds", 30)) * 2 + 60) if kind == "progress" else None,
        }

    def _emit(self, payload: dict[str, Any]) -> None:
        settings = self.get_settings()
        self.notifications.append(payload)
        if self.bus:
            self.bus.publish("notification", payload["sessionId"], payload)
        if payload["type"] not in settings.get("notifyOn", []):
            return
        if self.push is None:
            return
        if self._push_pool is not None:
            self._push_pool.submit(self._deliver_safe, payload)
        else:
            self._deliver_safe(payload)

    def _deliver_safe(self, payload: dict[str, Any]) -> None:
        try:
            self.push.deliver(payload)
        except Exception:
            log.exception("push delivery failed")

    def _alert(self, kind: str, session: dict | None, sid: str, title: str, body: str,
               discriminator: str = "", window: float = 60.0, progress: dict | None = None,
               stage: str | None = None) -> bool:
        now = self.clock()
        key = (sid, kind, discriminator)
        if now - self._last_alert.get(key, -1e18) < window:
            return False
        self._last_alert[key] = now
        if len(self._last_alert) > 2000:
            cutoff = now - 3600
            self._last_alert = {k: v for k, v in self._last_alert.items() if v > cutoff}
        self._pending_progress.pop(sid, None)
        self._emit(self._payload(kind, session, sid, title, body, progress, stage))
        return True

    def _queue_progress(self, sid: str, payload: dict[str, Any], force: bool = False) -> None:
        """Silent progress, throttled per session to ``progressIntervalSeconds``."""
        interval = float(self.get_settings().get("progressIntervalSeconds", 30))
        now = self.clock()
        if force or now - self._last_progress_push.get(sid, -1e18) >= interval:
            self._last_progress_push[sid] = now
            self._pending_progress.pop(sid, None)
            self._emit(payload)
        else:
            self._pending_progress[sid] = payload

    def _flush_progress(self) -> None:
        interval = float(self.get_settings().get("progressIntervalSeconds", 30))
        now = self.clock()
        for sid, payload in list(self._pending_progress.items()):
            if now - self._last_progress_push.get(sid, -1e18) >= interval:
                self._last_progress_push[sid] = now
                del self._pending_progress[sid]
                payload["timestamp"] = utc_iso(now)
                self._emit(payload)

    def agent_notify(self, sid: str, title: str, body: str, kind: str,
                     progress: dict | None, stage: str | None) -> dict[str, Any]:
        """Explicit notification from a local agent hook (``POST /notify``)."""
        session = self.try_resolve(sid)
        progress = clean_progress(progress)
        if session is not None and session.get("isSubagent"):
            parent = self.state.by_id.get(session.get("parentSessionId") or "")
            if kind in ("progress", "completed"):
                # A child finishing or progressing is not the chat finishing: show it in the
                # app only; the parent carries alerts, progress pushes and keep-awake.
                payload = self._payload(kind, session, session["id"], title, body, progress, stage)
                with self._state_lock:
                    self.notifications.append(payload)
                if self.bus:
                    self.bus.publish("notification", payload["sessionId"], payload)
                return payload
            if parent is not None:
                session = parent  # input/error need the user: alert on the controllable parent
        key = session["id"] if session else sid
        with self._state_lock:
            now = self.clock()
            self._leases[key] = _NotifyLease(kind, now, stage, progress)
            if len(self._leases) > 500:
                oldest = sorted(self._leases.items(), key=lambda kv: kv[1].at)[:100]
                for k, _ in oldest:
                    self._leases.pop(k, None)
            payload = self._payload(kind, session, key, title, body, progress, stage)
            if kind == "progress":
                self._queue_progress(key, payload)
            else:
                self._pending_progress.pop(key, None)
                self._last_alert[(key, kind, "agent")] = now
                self._emit(payload)
            self._update_keepawake()
        if self.bus:
            self.bus.publish("sessions")
        return payload

    # ---------------------------------------------------------------- poll
    def poll_once(self) -> None:
        st = self.refresh()
        with self._state_lock:
            self._process(st)

    def _process(self, st: HubState) -> None:
        now = self.clock()
        present = set()
        sigs = []
        self._poll_approvals(st)  # first, so approval alerts carry the prompt title
        for s in st.sessions:
            sid = s["id"]
            present.add(sid)
            sigs.append((sid, s["status"], s["updatedAt"], s.get("stage"), json.dumps(s.get("progress"))))
            if s.get("isSubagent"):
                # Children are inspectable but never ring, push progress or hold the Mac awake;
                # the parent session carries those signals.
                self._track_messages(s)
                continue
            kind = self.tracker.observe(sid, s["status"], now)
            if self._baseline_done:
                self._handle_transition(s, kind, now)
            self._track_activity(s)
        self.tracker.forget_missing(present)
        self._flush_progress()
        digest = hashlib.sha256(repr(sigs).encode()).hexdigest()
        if digest != self._list_hash:
            self._list_hash = digest
            if self.bus:
                self.bus.publish("sessions")
        self._baseline_done = True
        self._update_keepawake()

    def _recent_agent_alert(self, sid: str, kind: str, now: float, window: float = 120.0) -> bool:
        return now - self._last_alert.get((sid, kind, "agent"), -1e18) < window

    def _handle_transition(self, s: dict, kind: str | None, now: float) -> None:
        sid = s["id"]
        title = s.get("title") or sid
        if kind == "completed":
            self._leases.pop(sid, None)
            if not self._recent_agent_alert(sid, "completed", now):
                self._alert("completed", s, sid, title, s.get("lastMessage") or "Finished", "status", window=10)
        elif kind == "error":
            self._leases.pop(sid, None)
            if not self._recent_agent_alert(sid, "error", now):
                self._alert("error", s, sid, title, s.get("lastMessage") or "The agent reported an error", "status", window=10)
        elif kind == "input":
            recent = any(k[0] == sid and k[1] == "input" and now - v < 30 for k, v in self._last_alert.items())
            if not recent:
                self._alert("input", s, sid, title, s.get("stage") or "Waiting for your input", "status", window=10)

    def _track_messages(self, s: dict) -> None:
        sid = s["id"]
        sig = f"{s['updatedAt']}|{s.get('lastMessage')}"
        prev = self._last_seen_sig.get(sid)
        self._last_seen_sig[sid] = sig
        if prev is not None and prev != sig and self.bus:
            self.bus.publish("messages", sid)

    def _track_activity(self, s: dict) -> None:
        sid = s["id"]
        self._track_messages(s)
        if s["status"] != "working" or not self._baseline_done:
            self._last_progress_sig.pop(sid, None)
            if not self._progress_lease_active(sid, self.clock()):
                # A throttled "working" update must not be pushed after the work ended.
                self._pending_progress.pop(sid, None)
            return
        psig = f"{s.get('stage')}|{json.dumps(s.get('progress'))}"
        heartbeat = max(60, float(self.get_settings().get("progressIntervalSeconds", 30)))
        if self._last_progress_sig.get(sid) == psig and self.clock() - self._last_progress_push.get(sid, -1e18) < heartbeat:
            return
        first = sid not in self._last_progress_sig
        self._last_progress_sig[sid] = psig
        payload = self._payload("progress", s, sid, s.get("title") or sid,
                                s.get("stage") or "Working…", s.get("progress"), s.get("stage"))
        self._queue_progress(sid, payload, force=first)

    def _poll_approvals(self, st: HubState) -> None:
        if self.terminal is None:
            return
        live_ids = set()
        for s in st.sessions:
            if not s["managed"]:
                continue
            sid = s["id"]
            live_ids.add(sid)
            lock = self.session_lock(sid)
            if not lock.acquire(blocking=False):
                continue  # a control action is using the pane right now
            try:
                approvals = list(self.terminal.approvals(sid) or [])
            except Exception:
                approvals = []
            finally:
                lock.release()
            ids = {a.get("id") for a in approvals if isinstance(a, dict) and a.get("id")}
            known = self._known_approvals.get(sid, set())
            new = ids - known
            self._known_approvals[sid] = ids
            if ids != known and self.bus:
                self.bus.publish("approval", sid)
            if new and self._baseline_done:
                first = next(a for a in approvals if a.get("id") in new)
                self._alert("input", s, sid, s.get("title") or sid,
                            str(first.get("title") or "Permission requested"), discriminator=str(first.get("id")),
                            window=3600)
        for sid in list(self._known_approvals):
            if sid not in live_ids:
                del self._known_approvals[sid]

    def _update_keepawake(self) -> None:
        if self.keepawake is None:
            return
        with self._state_lock:
            active = any(self.tracker.is_working(s["id"]) for s in self.state.sessions) or self.notify_active()
        try:
            self.keepawake.update(active, self.get_settings().get("keepAwakeMode", "active"))
        except Exception:
            log.exception("keep-awake update failed")

    def shutdown(self) -> None:
        if self.keepawake is not None:
            self.keepawake.release()
        if self._push_pool is not None:
            self._push_pool.shutdown(wait=False, cancel_futures=True)
