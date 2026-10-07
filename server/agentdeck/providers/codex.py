"""Codex CLI: read-only thread discovery, history and live-process evidence.

Sources (never written):
- ``~/.codex/state_5.sqlite`` opened with ``mode=ro`` (threads table).
- rollout JSONL files referenced by ``threads.rollout_path`` under ``~/.codex/sessions``.
- ``ps``/``lsof`` to see which terminal Codex processes have a rollout open.
``auth.json`` and other credential files are never opened.
"""

from __future__ import annotations

import glob
import os
import re
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

from . import codex_daemon
from .common import (
    LAST_MESSAGE_CHARS,
    MAX_TOOL_CHARS,
    PROCS,
    TTLCache,
    clean_text,
    clip,
    codex_home,
    is_within,
    iter_json_lines,
    norm_iso,
    open_files,
    process_cwd,
    read_tail_lines,
    utc_iso,
)

AGENT = "codex"
MAX_SESSIONS = 60
MAX_AGE_DAYS = 30
TAIL_BYTES = 512 * 1024
HISTORY_MAX_BYTES = 16 * 1024 * 1024
_ROLLOUT_RE = re.compile(r"rollout-[^/]*?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$")
_INJECTED_PREFIXES = (
    "# AGENTS.md instructions", "<environment_context>", "<user_instructions>", "<permissions",
    "<turn_aborted>", "<user_shell_command>", "<collaboration_mode", "<skill>", "<subagent_notification",
)
_meta_cache = TTLCache()


def sources() -> tuple[str, ...]:
    """Legacy ``threads.source`` values that mean "terminal TUI" (pre-daemon Codex)."""
    raw = os.environ.get("AGENTDECK_CODEX_SOURCES", "cli")
    return tuple(s.strip() for s in raw.split(",") if s.strip())


def originators() -> tuple[str, ...]:
    """``threads.originator`` values of terminal TUIs (Codex >= 0.15x routes them via the daemon)."""
    raw = os.environ.get("AGENTDECK_CODEX_ORIGINATORS", "codex-tui")
    return tuple(s.strip() for s in raw.split(",") if s.strip())


def state_db() -> Path | None:
    home = codex_home()
    cands = sorted(glob.glob(str(home / "state_*.sqlite")))
    if not cands:
        return None
    # highest schema number wins (state_5 today)
    def num(p: str) -> int:
        m = re.search(r"state_(\d+)\.sqlite$", p)
        return int(m.group(1)) if m else -1
    return Path(max(cands, key=num))


def _connect() -> sqlite3.Connection | None:
    db = state_db()
    if not db:
        return None
    try:
        conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=2.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA query_only=ON")
        return conn
    except sqlite3.Error:
        return None


def _columns(conn: sqlite3.Connection) -> set[str]:
    return {r[1] for r in conn.execute("PRAGMA table_info(threads)")}


def query_threads(limit: int = MAX_SESSIONS, ids: list[str] | None = None,
                  cwd: str | None = None, created_after_ms: int | None = None) -> list[dict]:
    conn = _connect()
    if conn is None:
        return []
    try:
        cols = _columns(conn)
        want = [c for c in ("id", "rollout_path", "created_at", "updated_at", "created_at_ms", "updated_at_ms",
                            "source", "cwd", "title", "name", "preview", "first_user_message", "model",
                            "archived", "cli_version", "reasoning_effort", "originator") if c in cols]
        where, args = [], []
        if "archived" in cols:
            where.append("archived = 0")
        if ids:
            where.append(f"id IN ({','.join('?' * len(ids))})")
            args += ids
        else:
            srcs, origs = sources(), originators()
            terms = []
            if srcs and "source" in cols:
                terms.append(f"source IN ({','.join('?' * len(srcs))})")
                args += list(srcs)
            if origs and "originator" in cols:
                terms.append(f"originator IN ({','.join('?' * len(origs))})")
                args += list(origs)
            if terms:
                where.append("(" + " OR ".join(terms) + ")")
            if "thread_source" in cols:
                where.append("COALESCE(thread_source, '') != 'subagent'")
            if "source" in cols:
                # spawned children / guardian reviewers are JSON sources: never top-level chats
                where.append("source NOT LIKE '{%'")
            if cwd is not None:
                where.append("cwd = ?")
                args.append(cwd)
            if created_after_ms is not None and "created_at_ms" in cols:
                where.append("COALESCE(created_at_ms, created_at * 1000) >= ?")
                args.append(int(created_after_ms))
            elif "updated_at" in cols:
                where.append("updated_at >= ?")
                args.append(int(time.time() - MAX_AGE_DAYS * 86400))
        order = "COALESCE(updated_at_ms, updated_at * 1000)" if "updated_at_ms" in cols else "updated_at"
        if created_after_ms is not None:
            order = "COALESCE(created_at_ms, created_at * 1000)"
            direction = "ASC"
        else:
            direction = "DESC"
        sql = f"SELECT {', '.join(want)} FROM threads"
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += f" ORDER BY {order} {direction} LIMIT ?"
        args.append(int(limit))
        return [dict(r) for r in conn.execute(sql, args)]
    except sqlite3.Error:
        return []
    finally:
        conn.close()


# --- live process evidence ---------------------------------------------------

_live_lock = threading.Lock()
_live_cache: dict[str, Any] = {"at": 0.0, "value": None}


def _is_codex_tui(cmd: str) -> bool:
    if "app-server" in cmd or "exec-server" in cmd or "code-mode-host" in cmd or "mcp-server" in cmd:
        return False
    parts = cmd.split()
    if not parts:
        return False
    exe = os.path.basename(parts[0])
    if exe == "codex":
        sub = parts[1] if len(parts) > 1 else ""
        return sub in ("", "resume", "fork") or sub.startswith("-") or not sub.isalpha()
    # node launcher: node .../codex.js [args]
    if exe in ("node", "bun") and len(parts) > 1 and parts[1].endswith(("/codex", "/codex.js")):
        sub = parts[2] if len(parts) > 2 else ""
        return sub in ("", "resume", "fork") or sub.startswith("-") or not sub.isalpha()
    return False


def live_processes(max_age: float = 10.0) -> dict:
    """{'byThread': {threadId: {pid, how}}, 'unmapped': [{pid, cwd}]} for terminal Codex processes."""
    with _live_lock:
        if _live_cache["value"] is not None and time.monotonic() - _live_cache["at"] < max_age:
            return _live_cache["value"]
        rows = PROCS.rows()
        tui = [r for r in rows if r["tty"] not in ("??", "-", "") and _is_codex_tui(r["command"])]
        # prefer native binaries (children of node launchers)
        by_thread: dict[str, dict] = {}
        unmapped: list[dict] = []
        tui_pids = {r["pid"] for r in tui}
        for r in tui:
            if r["ppid"] in tui_pids:
                continue  # child of another codex launcher; handled through the parent group
            group = [r] + [c for c in rows if c["ppid"] == r["pid"]]
            found = None
            for proc in group:
                for f in open_files(proc["pid"]):
                    m = _ROLLOUT_RE.search(f)
                    if m:
                        found = m.group(1)
                        break
                if found:
                    break
                m = re.search(r"\bresume\s+([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", proc["command"])
                if m:
                    found = m.group(1)
                    break
            if found:
                by_thread[found] = {"pid": r["pid"], "how": "open-rollout-or-args"}
            else:
                unmapped.append({"pid": r["pid"], "cwd": process_cwd(r["pid"])})
        value = {"byThread": by_thread, "unmapped": unmapped}
        _live_cache.update(at=time.monotonic(), value=value)
        return value


def rollout_activity(path: Path) -> dict:
    """Turn state from the rollout tail: {'turnOpen': bool|None, 'lastTs': float|None, 'lastMessage': str|None}."""
    try:
        st = path.stat()
    except OSError:
        return {"turnOpen": None, "lastTs": None, "lastMessage": None, "model": None}
    stamp = (st.st_mtime_ns, st.st_size)
    hit = _meta_cache.get(str(path), stamp)
    if hit is not None:
        return hit
    lines, _ = read_tail_lines(path, 128 * 1024)
    turn_open = None
    last_msg = None
    model = None
    last_ts = None
    for e in iter_json_lines(lines):
        pl = e.get("payload") if isinstance(e.get("payload"), dict) else {}
        ts = e.get("timestamp")
        if ts:
            last_ts = ts
        if e.get("type") == "turn_context" and isinstance(pl.get("model"), str):
            model = pl["model"]
        if e.get("type") == "event_msg":
            t = pl.get("type")
            if t == "task_started":
                turn_open = True
            elif t in ("task_complete", "turn_aborted"):
                turn_open = False
                if t == "task_complete" and isinstance(pl.get("last_agent_message"), str):
                    last_msg = pl["last_agent_message"]
            elif t == "agent_message" and isinstance(pl.get("message"), str):
                last_msg = pl["message"]
    value = {
        "turnOpen": turn_open,
        "lastTs": last_ts,
        "lastMessage": clip(" ".join(clean_text(last_msg).split()), LAST_MESSAGE_CHARS) if last_msg else None,
        "model": model,
    }
    _meta_cache.put(str(path), stamp, value)
    return value


def _safe_rollout(path: Any) -> Path | None:
    if not isinstance(path, str) or not path.endswith(".jsonl"):
        return None
    p = Path(path)
    if not is_within(p, codex_home()):
        return None
    return p if p.is_file() else None


def _title(row: dict) -> str:
    for key in ("name", "title", "preview", "first_user_message"):
        v = row.get(key)
        if isinstance(v, str) and v.strip() and not v.lstrip().startswith(_INJECTED_PREFIXES):
            return clip(" ".join(v.split()), 80)
    return os.path.basename(row.get("cwd") or "") or "Codex chat"


def daemon_status(loaded: dict) -> tuple[str, str | None]:
    st = loaded.get("status")
    flags = loaded.get("activeFlags") or []
    if st == "active":
        if "waitingOnApproval" in flags:
            return "needs_input", "Waiting for approval"
        if "waitingOnUserInput" in flags:
            return "needs_input", "Waiting for input"
        return "working", None
    if st == "idle":
        return "idle", None
    if st == "systemError":
        return "error", "Codex reported a system error"
    return "unknown", "Loaded in Codex"


def discover_sessions() -> list[dict]:
    rows = query_threads()
    daemon = codex_daemon.loaded_threads()
    live = live_processes()
    known_rows = {r.get("id") for r in rows}
    missing_live = [tid for tid in live["byThread"] if tid not in known_rows]
    if missing_live:
        rows.extend(query_threads(ids=missing_live, limit=len(missing_live)))
    out: list[dict] = []
    for row in rows:
        tid = row.get("id")
        if not isinstance(tid, str):
            continue
        rollout = _safe_rollout(row.get("rollout_path"))
        act = rollout_activity(rollout) if rollout else {"turnOpen": None, "lastTs": None, "lastMessage": None, "model": None}
        loaded = (daemon or {}).get(tid)
        live_entry = live["byThread"].get(tid) or ({"pid": None} if loaded else None)
        cwd = row.get("cwd")
        # cwd-only evidence is too weak when the daemon can tell us exactly which threads are loaded
        possibly_live = daemon is None and (not live_entry) and any(u.get("cwd") == cwd for u in live["unmapped"])
        if loaded:
            status, stage = daemon_status(loaded)
            evidence = "codex-daemon-thread-status"
        elif live_entry:
            if act["turnOpen"]:
                status, stage = "working", None
            elif act["turnOpen"] is False:
                status, stage = "idle", None
            else:
                status, stage = "unknown", "Running in a terminal"
            evidence = "codex-process-open-rollout"
        elif possibly_live:
            status, stage = "unknown", "A Codex terminal is open in this folder"
            evidence = "codex-process-cwd"
        else:
            status, stage = "offline", "Not running · history only"
            evidence = "state-db"
        upd_ms = row.get("updated_at_ms") or (row.get("updated_at") or 0) * 1000
        out.append({
            "id": f"codex:{tid}",
            "agent": AGENT,
            "nativeId": tid,
            "title": _title(row),
            "cwd": cwd,
            "model": row.get("model") or act.get("model"),
            "status": status,
            "stage": stage,
            "progress": None,
            "updatedAt": utc_iso(upd_ms / 1000.0 if upd_ms else None),
            "managed": False,
            "capabilities": {"send": False, "interrupt": False, "approve": False, "stop": False},
            "lastMessage": act.get("lastMessage"),
            "unread": 0,
            "live": bool(live_entry),
            "canResume": not live_entry and not possibly_live,
            "statusEvidence": evidence,
            "livePid": (live_entry or {}).get("pid"),
            "_rolloutPath": str(rollout) if rollout else None,
        })
    # Live terminal threads the daemon has loaded but that have no state-db row yet
    # (a freshly opened TUI before its first message).
    known = {s["nativeId"] for s in out}
    for tid, loaded in (daemon or {}).items():
        if tid in known or loaded.get("originator") not in originators():
            continue
        status, stage = daemon_status(loaded)
        out.append({
            "id": f"codex:{tid}", "agent": AGENT, "nativeId": tid, "title": "New Codex chat",
            "cwd": loaded.get("cwd"), "model": None, "status": status, "stage": stage, "progress": None,
            "updatedAt": utc_iso(loaded.get("updatedAt") or None), "managed": False,
            "capabilities": {"send": False, "interrupt": False, "approve": False, "stop": False},
            "lastMessage": None, "unread": 0, "live": True, "canResume": False,
            "statusEvidence": "codex-daemon-thread-status", "livePid": None, "_rolloutPath": None,
        })
    return out


def lookup(native_id: str) -> dict | None:
    rows = query_threads(ids=[native_id], limit=1)
    if not rows:
        return None
    row = rows[0]
    rollout = _safe_rollout(row.get("rollout_path"))
    act = rollout_activity(rollout) if rollout else {}
    return {"title": _title(row), "model": row.get("model") or act.get("model"), "cwd": row.get("cwd"),
            "lastMessage": act.get("lastMessage"), "path": str(rollout) if rollout else None,
            "turnOpen": act.get("turnOpen")}


def find_new_thread(cwd: str, started_ms: int, exclude: set[str]) -> str | None:
    """First CLI thread created in ``cwd`` after ``started_ms`` not already claimed."""
    for row in query_threads(limit=10, cwd=cwd, created_after_ms=started_ms - 2000):
        tid = row.get("id")
        if isinstance(tid, str) and tid not in exclude:
            return tid
    return None


def thread_for_pids(pids: list[int]) -> str | None:
    for pid in pids:
        for f in open_files(pid):
            m = _ROLLOUT_RE.search(f)
            if m:
                return m.group(1)
    return None


# --- history -------------------------------------------------------------

def read_messages(native_id: str | None, path: str | None, limit: int = 100) -> list[dict]:
    p = _safe_rollout(path) if path else None
    if p is None and native_id:
        info = lookup(native_id)
        p = _safe_rollout(info.get("path")) if info else None
    if p is None:
        return []
    limit = max(1, min(int(limit or 100), 500))
    budget = TAIL_BYTES
    while True:
        lines, at_start = read_tail_lines(p, budget)
        msgs = parse_rollout(lines)
        if len(msgs) >= limit or at_start or budget >= HISTORY_MAX_BYTES:
            return msgs[-limit:]
        budget *= 4


def _content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(
            b.get("text", "") for b in content
            if isinstance(b, dict) and str(b.get("type", "")).lower() in ("text", "input_text", "output_text")
            and isinstance(b.get("text"), str) and b.get("text")
        )
    return ""


def _injected(text: str) -> bool:
    return text.lstrip().startswith(_INJECTED_PREFIXES)


def parse_rollout(lines: list[str]) -> list[dict]:
    entries = list(iter_json_lines(lines))
    has_items = any(
        e.get("type") == "event_msg" and isinstance(e.get("payload"), dict)
        and e["payload"].get("type") in ("item_completed", "user_message", "agent_message")
        for e in entries
    )
    out: list[dict] = []
    recent: list[tuple[str, str]] = []

    def emit(mid: str, role: str, text: str, ts: str, tool: str | None = None) -> None:
        if not text:
            return
        key = (role, text)
        if key in recent:
            return
        recent.append(key)
        del recent[:-12]
        out.append({"id": mid, "role": role, "text": text, "timestamp": ts, "toolName": tool})

    # A forked child rollout starts with its own session_meta, then the parent's
    # session_meta + copied (compacted) parent context. Skip that inherited prefix
    # up to the child's first own task_started so the parent's words aren't shown as the child's.
    own_start = 0
    metas = [i for i, e in enumerate(entries) if e.get("type") == "session_meta" and isinstance(e.get("payload"), dict)]
    if metas and metas[0] == 0:
        own_id = entries[0]["payload"].get("id")
        foreign = [i for i in metas if entries[i]["payload"].get("id") not in (None, own_id)]
        if foreign:
            own_start = next((i for i in range(foreign[-1], len(entries))
                              if entries[i].get("type") == "event_msg" and isinstance(entries[i].get("payload"), dict)
                              and entries[i]["payload"].get("type") == "task_started"), 0)

    for n, e in enumerate(entries):
        if n < own_start:
            continue
        pl = e.get("payload") if isinstance(e.get("payload"), dict) else {}
        ts = norm_iso(e.get("timestamp"))
        typ, pt = e.get("type"), pl.get("type")
        if typ == "event_msg":
            if pt == "item_completed" and isinstance(pl.get("item"), dict):
                it = pl["item"]
                iid = str(it.get("id") or n)
                kind = it.get("type")
                if kind == "UserMessage":
                    txt = _content_text(it.get("content"))
                    if not _injected(txt):
                        emit(iid, "user", clean_text(txt), ts)
                elif kind == "AgentMessage":
                    emit(iid, "assistant", clean_text(_content_text(it.get("content"))), ts)
                elif kind == "CommandExecution":
                    cmd = it.get("command")
                    cmd_s = " ".join(cmd) if isinstance(cmd, list) else str(cmd or "")
                    code = it.get("exit_code")
                    suffix = f" (exit {code})" if code not in (None, 0) else ""
                    emit(iid, "tool", clean_text(f"$ {cmd_s}{suffix}", MAX_TOOL_CHARS), ts, "shell")
                elif kind == "FileChange":
                    ch = it.get("changes")
                    names = [os.path.basename(k) for k in ch.keys()] if isinstance(ch, dict) else []
                    emit(iid, "tool", clean_text("Edited " + ", ".join(names[:8]) + (" …" if len(names) > 8 else ""), MAX_TOOL_CHARS), ts, "apply_patch")
                elif kind == "McpToolCall":
                    name = f"{it.get('server')}.{it.get('tool')}"
                    emit(iid, "tool", clean_text(name, MAX_TOOL_CHARS), ts, name)
                elif kind == "Extension":
                    ext = str(it.get("kind") or "extension")
                    detail = it.get("query") if isinstance(it.get("query"), str) else ""
                    emit(iid, "tool", clean_text(f"{ext}: {detail}" if detail else ext, MAX_TOOL_CHARS), ts, ext)
                elif kind == "SubAgentActivity":
                    who = it.get("agent_path") or it.get("agent_thread_id") or "subagent"
                    emit(iid, "tool", clean_text(f"Subagent {who}", MAX_TOOL_CHARS), ts, "subagent")
                elif kind == "ContextCompaction":
                    emit(iid, "system", "Context compacted", ts)
            elif pt == "user_message" and isinstance(pl.get("message"), str):
                if not _injected(pl["message"]):
                    emit(f"um{n}", "user", clean_text(pl["message"]), ts)
            elif pt == "agent_message" and isinstance(pl.get("message"), str):
                emit(f"am{n}", "assistant", clean_text(pl["message"]), ts)
            elif pt == "turn_aborted":
                emit(f"ab{n}", "system", "Turn interrupted", ts)
            elif pt == "error":
                emit(f"er{n}", "system", "Error: " + clean_text(str(pl.get("message") or "unknown error"), MAX_TOOL_CHARS), ts)
            elif pt == "task_complete" and isinstance(pl.get("last_agent_message"), str):
                emit(f"tc{n}", "assistant", clean_text(pl["last_agent_message"]), ts)
        elif typ == "response_item" and pt == "message" and pl.get("role") == "user" and has_items:
            # spawned children often record their task only as a response_item; dedupe covers the rest
            txt = _content_text(pl.get("content"))
            if not _injected(txt):
                emit(str(pl.get("id") or f"ri{n}"), "user", clean_text(txt), ts)
        elif typ == "response_item" and not has_items:
            if pt == "message" and pl.get("role") in ("user", "assistant"):
                txt = _content_text(pl.get("content"))
                if pl["role"] == "user" and _injected(txt):
                    continue
                emit(str(pl.get("id") or f"ri{n}"), pl["role"], clean_text(txt), ts)
            elif pt in ("function_call", "custom_tool_call"):
                name = str(pl.get("name") or "tool")
                arg = pl.get("arguments") if pt == "function_call" else pl.get("input")
                emit(str(pl.get("call_id") or n), "tool", clean_text(f"{name}: {arg}", MAX_TOOL_CHARS), ts, name)
    return out
