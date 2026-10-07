"""Real subagents (child agents) of discovered Claude Code and Codex sessions.

Read-only and bounded. Every child row is backed by a native child record:
- Claude Code: ``projects/<slug>/<parentSessionId>/subagents/agent-<agentId>.jsonl``
  plus ``agent-<agentId>.meta.json`` ({agentType, description, toolUseId, spawnDepth}).
  Final state comes from the parent's ``<task-notification>`` for that task-id
  (``<status>completed|failed|killed|stopped</status>``) when it is newer than the
  child's last record; otherwise from the child's own transcript tail.
- Codex: ``state_5.sqlite`` ``thread_spawn_edges`` + ``threads`` (agent_nickname,
  agent_role, agent_path). Edge status stays ``open`` after a child finishes, so
  status comes from the daemon (if the child thread is loaded) or the child's
  rollout tail (task_complete / error / turn_aborted / recent activity).

A child's status is never copied from its parent. Children have no direct
controls: send/interrupt/approve/stop are always false.
"""

from __future__ import annotations

import json
import re
import sqlite3
import time
from pathlib import Path
from typing import Any

from . import claude, codex, codex_daemon
from .common import (
    LAST_MESSAGE_CHARS,
    TTLCache,
    clean_text,
    clip,
    is_within,
    iter_json_lines,
    norm_iso,
    parse_iso,
    read_head_lines,
    read_tail_lines,
    utc_iso,
)

MAX_CHILDREN_PER_PARENT = 40
MAX_CHILDREN_TOTAL = 200
MAX_CODEX_DEPTH = 4
CHILD_HEAD_BYTES = 64 * 1024
CHILD_TAIL_BYTES = 256 * 1024
PARENT_NOTIFY_TAIL_BYTES = 4 * 1024 * 1024
NESTED_SEARCH_BYTES = 2 * 1024 * 1024
ACTIVE_WINDOW = 90.0  # seconds of transcript silence still counted as "working"
TASK_CHARS = 600
NO_CONTROLS = {"send": False, "interrupt": False, "approve": False, "stop": False}

_child_cache = TTLCache()
_notify_cache = TTLCache(max_items=128)

_NOTIFY_RE = re.compile(r"<task-id>([A-Za-z0-9_-]+)</task-id>.*?<status>([a-z_]+)</status>(?:.*?<summary>(.*?)</summary>)?", re.S)
CLAUDE_FINAL = {
    "completed": ("completed", None),
    "failed": ("error", None),
    "killed": ("idle", "Stopped"),
    "stopped": ("idle", "Stopped"),
}


def child_id(agent: str, native_id: str) -> str:
    return f"claude-sub:{native_id}" if agent == "claude" else f"codex:{native_id}"


def discover(parents: list[dict], now: float | None = None) -> list[dict]:
    """Child sessions for the given discovered parent sessions (claude + codex)."""
    now = time.time() if now is None else now
    out = claude_children([p for p in parents if p.get("agent") == "claude"], now)
    out += codex_children([p for p in parents if p.get("agent") == "codex"], now)
    return out[:MAX_CHILDREN_TOTAL]


# --- Claude Code ------------------------------------------------------------

def _claude_notifications(parent_path: Path) -> dict[str, dict]:
    """agentId -> {status, ts, summary} from the newest task-notifications in the parent tail."""
    try:
        st = parent_path.stat()
    except OSError:
        return {}
    stamp = (st.st_mtime_ns, st.st_size)
    hit = _notify_cache.get(str(parent_path), stamp)
    if hit is not None:
        return hit
    lines, _ = read_tail_lines(parent_path, PARENT_NOTIFY_TAIL_BYTES)
    found: dict[str, dict] = {}
    for ln in lines:
        if "<task-notification>" not in ln or "<status>" not in ln:
            continue
        try:
            rec = json.loads(ln)
        except ValueError:
            continue
        ts = parse_iso(rec.get("timestamp"))
        for m in _NOTIFY_RE.finditer(json.dumps(rec, ensure_ascii=False).replace("\\n", "\n")):
            aid, status, summary = m.group(1), m.group(2), m.group(3)
            prev = found.get(aid)
            if prev is None or (ts or 0) >= (prev["ts"] or 0):
                found[aid] = {"status": status, "ts": ts,
                              "summary": clip(" ".join((summary or "").replace('\\"', '"').split()), 200) or None}
    _notify_cache.put(str(parent_path), stamp, found)
    return found


def _scan_claude_child(path: Path) -> dict:
    st = path.stat()
    stamp = (st.st_mtime_ns, st.st_size)
    hit = _child_cache.get(str(path), stamp)
    if hit is not None:
        return hit
    info: dict[str, Any] = {"task": None, "cwd": None, "model": None, "lastTs": None, "lastMessage": None,
                            "final": False, "pendingTool": None, "mtime": st.st_mtime}
    for e in iter_json_lines(read_head_lines(path, CHILD_HEAD_BYTES)):
        if info["cwd"] is None and isinstance(e.get("cwd"), str):
            info["cwd"] = e["cwd"]
        if info["task"] is None and e.get("type") == "user":
            txt = claude._text_of((e.get("message") or {}).get("content"))
            if txt:
                info["task"] = clip(clean_text(txt), TASK_CHARS)
        if info["task"] and info["cwd"]:
            break
    tail_lines, _ = read_tail_lines(path, CHILD_TAIL_BYTES)
    pending: dict[str, str] = {}
    for e in iter_json_lines(tail_lines):
        t = e.get("type")
        if t not in ("user", "assistant"):
            continue
        ts = parse_iso(e.get("timestamp"))
        if ts:
            info["lastTs"] = max(info["lastTs"] or 0, ts)
        if isinstance(e.get("cwd"), str):
            info["cwd"] = e["cwd"]
        msg = e.get("message") or {}
        content = msg.get("content")
        if t == "assistant":
            if isinstance(msg.get("model"), str) and msg["model"] != "<synthetic>":
                info["model"] = msg["model"]
            info["final"] = msg.get("stop_reason") == "end_turn"
            for b in content if isinstance(content, list) else []:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text" and b.get("text", "").strip():
                    info["lastMessage"] = clip(" ".join(clean_text(b["text"]).split()), LAST_MESSAGE_CHARS)
                elif b.get("type") == "tool_use":
                    summary = claude._tool_summary(str(b.get("name") or "tool"), b.get("input"))
                    pending[str(b.get("id"))] = clip(" ".join(clean_text(summary).split()), LAST_MESSAGE_CHARS)
        else:
            info["final"] = False
            for b in content if isinstance(content, list) else []:
                if isinstance(b, dict) and b.get("type") == "tool_result":
                    pending.pop(str(b.get("tool_use_id")), None)
    info["pendingTool"] = list(pending.values())[-1] if pending else None
    _child_cache.put(str(path), stamp, info)
    return info


def _claude_status(info: dict, notif: dict | None, parent_live: bool, now: float) -> tuple[str, str | None, str]:
    last = info.get("lastTs") or info["mtime"]
    if notif and (notif.get("ts") or 0) >= last - 2:
        status, stage = CLAUDE_FINAL.get(notif["status"], ("unknown", f"Reported {notif['status']}"))
        return status, notif.get("summary") if status == "error" else stage, "claude-task-notification"
    if now - info["mtime"] <= ACTIVE_WINDOW and not info["final"]:
        return "working", info.get("pendingTool"), "subagent-transcript-activity"
    if info["final"] and not info.get("pendingTool"):
        return "completed", None, "subagent-transcript-final"
    if not parent_live:
        return "offline", "Stopped without a final report", "subagent-transcript"
    return "unknown", "No recent activity", "subagent-transcript"


def _claude_nested_parent(child_meta: dict, siblings: list[Path], self_path: Path) -> str | None:
    """For spawnDepth > 1: agentId of the sibling whose transcript issued this child's toolUseId."""
    tuid = child_meta.get("toolUseId")
    if not isinstance(tuid, str) or not tuid:
        return None
    needle = f'"id":"{tuid}"'.encode()
    for sib in siblings:
        if sib == self_path:
            continue
        try:
            with open(sib, "rb") as fh:
                if needle in fh.read(NESTED_SEARCH_BYTES):
                    return sib.stem.removeprefix("agent-")
        except OSError:
            continue
    return None


def claude_children(parents: list[dict], now: float) -> list[dict]:
    root = claude.projects_dir()
    out: list[dict] = []
    for parent in parents:
        tp = parent.get("_transcriptPath")
        if not isinstance(tp, str) or not tp.endswith(".jsonl"):
            continue
        ppath = Path(tp)
        sub = ppath.with_suffix("") / "subagents"
        if not is_within(sub, root) or not sub.is_dir():
            continue
        try:
            files = sorted((f for f in sub.glob("agent-*.jsonl") if f.is_file()),
                           key=lambda f: f.stat().st_mtime, reverse=True)[:MAX_CHILDREN_PER_PARENT]
        except OSError:
            continue
        if not files:
            continue
        notifs = _claude_notifications(ppath)
        parent_live = bool(parent.get("live"))
        for f in files:
            aid = f.stem.removeprefix("agent-")
            try:
                meta = json.loads(f.with_suffix(".meta.json").read_text()) if f.with_suffix(".meta.json").is_file() else {}
                info = _scan_claude_child(f)
            except (OSError, ValueError):
                continue
            meta = meta if isinstance(meta, dict) else {}
            status, stage, evidence = _claude_status(info, notifs.get(aid), parent_live, now)
            parent_id = parent["id"]
            depth = meta.get("spawnDepth") if isinstance(meta.get("spawnDepth"), int) else 1
            if depth > 1:
                nested = _claude_nested_parent(meta, files, f)
                if nested:
                    parent_id = child_id("claude", nested)
            desc = meta.get("description") if isinstance(meta.get("description"), str) else None
            out.append({
                "id": child_id("claude", aid),
                "agent": "claude",
                "nativeId": aid,
                "title": clip(" ".join((desc or info.get("task") or "Subagent").split()), 80),
                "cwd": info.get("cwd") or parent.get("cwd"),
                "model": info.get("model"),
                "status": status,
                "stage": stage,
                "progress": None,
                "updatedAt": utc_iso(info.get("lastTs") or info["mtime"]),
                "managed": False,
                "capabilities": dict(NO_CONTROLS),
                "lastMessage": info.get("lastMessage") or info.get("pendingTool"),
                "unread": 0,
                "parentSessionId": parent_id,
                "agentName": clip(desc, 80) if desc else None,
                "agentRole": meta.get("agentType") if isinstance(meta.get("agentType"), str) else None,
                "isSubagent": True,
                "task": info.get("task"),
                "spawnDepth": depth,
                "live": status == "working",
                "canResume": False,
                "statusEvidence": evidence,
                "livePid": None,
                "_transcriptPath": str(f),
                "_subagent": True,
            })
    return out


# --- Codex ------------------------------------------------------------------

def _codex_rows(parent_ids: list[str]) -> list[dict]:
    """BFS over thread_spawn_edges from the given parents (bounded depth / count)."""
    conn = codex._connect()
    if conn is None or not parent_ids:
        return []
    try:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "thread_spawn_edges" not in tables:
            return []
        cols = codex._columns(conn)
        want = [c for c in ("id", "rollout_path", "cwd", "title", "name", "preview", "first_user_message", "model",
                            "agent_nickname", "agent_role", "agent_path", "updated_at", "updated_at_ms") if c in cols]
        rows: list[dict] = []
        frontier, seen, depth = list(parent_ids), set(parent_ids), 1
        while frontier and depth <= MAX_CODEX_DEPTH and len(rows) < MAX_CHILDREN_TOTAL:
            q = (f"SELECT e.parent_thread_id AS _parent, e.status AS _edge, {', '.join('t.' + c for c in want)} "
                 f"FROM thread_spawn_edges e JOIN threads t ON t.id = e.child_thread_id "
                 f"WHERE e.parent_thread_id IN ({','.join('?' * len(frontier))}) "
                 f"ORDER BY COALESCE(t.updated_at_ms, t.updated_at * 1000) DESC LIMIT ?")
            batch = [dict(r) for r in conn.execute(q, [*frontier, MAX_CHILDREN_TOTAL - len(rows)])]
            per_parent: dict[str, int] = {}
            nxt = []
            for r in batch:
                if r["id"] in seen or per_parent.get(r["_parent"], 0) >= MAX_CHILDREN_PER_PARENT:
                    continue
                per_parent[r["_parent"]] = per_parent.get(r["_parent"], 0) + 1
                seen.add(r["id"])
                r["_depth"] = depth
                rows.append(r)
                nxt.append(r["id"])
            frontier, depth = nxt, depth + 1
        return rows
    except sqlite3.Error:
        return []
    finally:
        conn.close()


def _codex_tail(path: Path) -> dict:
    st = path.stat()
    stamp = (st.st_mtime_ns, st.st_size)
    hit = _child_cache.get(str(path), stamp)
    if hit is not None:
        return hit
    lines, _ = read_tail_lines(path, CHILD_TAIL_BYTES)
    info: dict[str, Any] = {"state": None, "error": None, "lastTs": None, "lastMessage": None, "activity": None,
                            "model": None, "mtime": st.st_mtime}
    for e in iter_json_lines(lines):
        pl = e.get("payload") if isinstance(e.get("payload"), dict) else {}
        ts = parse_iso(e.get("timestamp"))
        if ts:
            info["lastTs"] = ts
        if e.get("type") == "turn_context" and isinstance(pl.get("model"), str):
            info["model"] = pl["model"]
        if e.get("type") != "event_msg":
            continue
        t = pl.get("type")
        if t == "task_started":
            info["state"], info["error"] = "open", None
        elif t == "task_complete":
            info["state"] = "complete"
            if isinstance(pl.get("last_agent_message"), str):
                info["lastMessage"] = pl["last_agent_message"]
        elif t == "turn_aborted":
            info["state"] = "aborted"
        elif t == "error":
            info["state"], info["error"] = "error", pl.get("message")
        elif t == "agent_message" and isinstance(pl.get("message"), str):
            info["lastMessage"] = pl["message"]
        elif t == "item_completed" and isinstance(pl.get("item"), dict):
            it = pl["item"]
            if it.get("type") == "AgentMessage":
                txt = codex._content_text([{"type": "text", "text": b.get("text")} for b in it.get("content") or []
                                           if isinstance(b, dict) and b.get("text")])
                if txt:
                    info["lastMessage"] = txt
            elif it.get("type") == "CommandExecution":
                cmd = it.get("command")
                cmd_s = cmd[-1] if isinstance(cmd, list) and cmd else str(cmd or "")
                info["activity"] = f"$ {cmd_s}"
            elif it.get("type") == "FileChange":
                info["activity"] = "Editing files"
    for k in ("lastMessage", "activity"):
        if info[k]:
            info[k] = clip(" ".join(clean_text(info[k]).split()), LAST_MESSAGE_CHARS)
    if info["error"]:
        info["error"] = clip(" ".join(clean_text(str(info["error"])).split()), 200)
    _child_cache.put(str(path), stamp, info)
    return info


def _codex_status(info: dict | None, loaded: dict | None, parent_live: bool, now: float) -> tuple[str, str | None, str]:
    if loaded:
        status, stage = codex.daemon_status(loaded)
        return status, stage or (info or {}).get("activity"), "codex-daemon-thread-status"
    if not info:
        return "unknown", "No rollout recorded", "state-db"
    state = info["state"]
    if state == "complete":
        return "completed", None, "codex-rollout-task-complete"
    if state == "error":
        return "error", info.get("error"), "codex-rollout-error"
    if state == "aborted":
        return "idle", "Interrupted", "codex-rollout-turn-aborted"
    if now - info["mtime"] <= ACTIVE_WINDOW:
        return "working", info.get("activity"), "codex-rollout-activity"
    if not parent_live:
        return "offline", "Stopped without a final report", "codex-rollout"
    return "unknown", "No recent activity", "codex-rollout"


def codex_children(parents: list[dict], now: float) -> list[dict]:
    by_native = {p["nativeId"]: p for p in parents if isinstance(p.get("nativeId"), str)}
    rows = _codex_rows(list(by_native))
    if not rows:
        return []
    daemon = codex_daemon.loaded_threads() or {}
    ids = {native: p["id"] for native, p in by_native.items()}
    live_roots = {native for native, p in by_native.items() if p.get("live")}
    out: list[dict] = []
    for r in rows:
        tid = r["id"]
        ids[tid] = child_id("codex", tid)
        parent_live = r["_parent"] in live_roots
        if parent_live:
            live_roots.add(tid)
        rollout = codex._safe_rollout(r.get("rollout_path"))
        try:
            info = _codex_tail(rollout) if rollout else None
        except OSError:
            info = None
        status, stage, evidence = _codex_status(info, daemon.get(tid), parent_live, now)
        upd_ms = r.get("updated_at_ms") or (r.get("updated_at") or 0) * 1000
        updated = max(upd_ms / 1000.0 if upd_ms else 0, (info or {}).get("lastTs") or 0) or None
        task = r.get("first_user_message") if isinstance(r.get("first_user_message"), str) else None
        nick = r.get("agent_nickname") if isinstance(r.get("agent_nickname"), str) else None
        title_src = nick or codex._title(r)
        out.append({
            "id": ids[tid],
            "agent": "codex",
            "nativeId": tid,
            "title": clip(" ".join(title_src.split()), 80),
            "cwd": r.get("cwd") or by_native.get(r["_parent"], {}).get("cwd"),
            "model": r.get("model") or (info or {}).get("model"),
            "status": status,
            "stage": stage,
            "progress": None,
            "updatedAt": utc_iso(updated),
            "managed": False,
            "capabilities": dict(NO_CONTROLS),
            "lastMessage": (info or {}).get("lastMessage") or (info or {}).get("activity"),
            "unread": 0,
            "parentSessionId": ids.get(r["_parent"]),
            "agentName": nick,
            "agentRole": r.get("agent_role") if isinstance(r.get("agent_role"), str) else None,
            "agentPath": r.get("agent_path") if isinstance(r.get("agent_path"), str) else None,
            "isSubagent": True,
            "task": clip(clean_text(task), TASK_CHARS) if task else None,
            "spawnDepth": r["_depth"],
            "live": status == "working",
            "canResume": False,
            "statusEvidence": evidence,
            "livePid": None,
            "_rolloutPath": str(rollout) if rollout else None,
            "_subagent": True,
        })
    return out


# --- history ----------------------------------------------------------------

def read_messages(session: dict, limit: int = 100) -> list[dict]:
    """Messages of one child, read from its own transcript only."""
    if session.get("agent") == "codex":
        return codex.read_messages(session.get("nativeId"), session.get("_rolloutPath"), limit)
    path = session.get("_transcriptPath")
    if not isinstance(path, str) or not path.endswith(".jsonl"):
        return []
    p = Path(path)
    if not is_within(p, claude.projects_dir()) or p.parent.name != "subagents" or not p.is_file():
        return []
    limit = max(1, min(int(limit or 100), 500))
    budget = CHILD_TAIL_BYTES * 4
    while True:
        lines, at_start = read_tail_lines(p, budget)
        msgs = claude._parse_messages(lines, include_sidechain=True)
        if len(msgs) >= limit or at_start or budget >= claude.HISTORY_MAX_BYTES:
            return msgs[-limit:]
        budget *= 4
