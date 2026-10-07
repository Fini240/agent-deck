"""Claude Code: read-only transcript discovery, history and live-process evidence.

Sources (never written):
- ``~/.claude/projects/<slug>/<sessionId>.jsonl`` transcripts (only ``*.jsonl``
  directly inside a project dir; subagent folders and ``*.key`` files are ignored).
- ``~/.claude/sessions/<pid>.json`` live-process registry written by running
  Claude Code processes (``sessionId``, ``cwd``, ``status`` busy/idle/...).
  ``*.key`` files beside them are never opened.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any

from .common import (
    LAST_MESSAGE_CHARS,
    MAX_TOOL_CHARS,
    TTLCache,
    claude_home,
    clean_text,
    clip,
    is_within,
    iter_json_lines,
    norm_iso,
    parse_iso,
    pid_alive,
    read_head_lines,
    read_tail_lines,
    utc_iso,
)

AGENT = "claude"
MAX_SESSIONS = 60
MAX_AGE_DAYS = 30
HEAD_BYTES = 64 * 1024
TAIL_BYTES = 256 * 1024
HISTORY_MAX_BYTES = 8 * 1024 * 1024
# Headless `claude -p` runs are real CLI work and must be inspectable too.
# They remain read-only; terminal control is granted only by TerminalManager.
NON_TERMINAL_ENTRYPOINTS = ("mcp", "sdk-ts", "sdk-py")

_meta_cache = TTLCache()

LIVE_STATUS_MAP = {
    "busy": "working",
    "working": "working",
    "running": "working",
    "idle": "idle",
    "waiting": "needs_input",
    "waiting_for_input": "needs_input",
    "needs_input": "needs_input",
    "permission": "needs_input",
}


def projects_dir() -> Path:
    return claude_home() / "projects"


def sessions_dir() -> Path:
    return claude_home() / "sessions"


def live_registry() -> dict[str, dict]:
    """sessionId -> {pid, status, cwd, kind, entrypoint, version, updatedAt} for live processes."""
    out: dict[str, dict] = {}
    d = sessions_dir()
    try:
        entries = list(d.iterdir())
    except OSError:
        return out
    for p in entries:
        if p.suffix != ".json" or not p.name[:-5].isdigit():
            continue  # never touch *.key or anything else
        try:
            if p.stat().st_size > 64 * 1024:
                continue
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict):
            continue
        sid = data.get("sessionId")
        pid = data.get("pid")
        if not isinstance(sid, str) or not pid_alive(pid):
            continue
        prev = out.get(sid)
        upd = data.get("statusUpdatedAt") or data.get("updatedAt") or 0
        if prev and (prev.get("_upd") or 0) > upd:
            continue
        out[sid] = {
            "pid": int(pid),
            "status": data.get("status"),
            "cwd": data.get("cwd"),
            "kind": data.get("kind"),
            "entrypoint": data.get("entrypoint"),
            "version": data.get("version"),
            "updatedAt": norm_iso(upd) if upd else None,
            "_upd": upd,
        }
    return out


def session_for_pid(pid: int) -> dict | None:
    """Registry entry for one specific Claude process (used for managed panes)."""
    p = sessions_dir() / f"{int(pid)}.json"
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) and data.get("pid") == int(pid) else None


def transcript_path(native_id: str) -> Path | None:
    if not native_id or "/" in native_id or native_id.startswith("."):
        return None
    try:
        for proj in projects_dir().iterdir():
            cand = proj / f"{native_id}.jsonl"
            if cand.is_file():
                return cand
    except OSError:
        return None
    return None


def subagent_transcript(agent_id: str) -> Path | None:
    """``projects/*/<parent>/subagents/agent-<agentId>.jsonl`` for a child id (bounded glob)."""
    if not agent_id or not agent_id.replace("-", "").replace("_", "").isalnum():
        return None
    for cand in projects_dir().glob(f"*/*/subagents/agent-{agent_id}.jsonl"):
        if cand.is_file():
            return cand
    return None


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
        return "\n".join(p for p in parts if p)
    return ""


def _is_noise_user_text(text: str) -> bool:
    t = text.lstrip()
    return t.startswith(("<local-command-", "<command-", "<system-reminder>", "Caveat: ", "<bash-", "<task-notification"))


def _scan_meta(path: Path) -> dict | None:
    st = path.stat()
    stamp = (st.st_mtime_ns, st.st_size)
    cached = _meta_cache.get(str(path), stamp)
    if cached is not None:
        return cached
    head = list(iter_json_lines(read_head_lines(path, HEAD_BYTES)))
    tail_lines, _ = read_tail_lines(path, TAIL_BYTES)
    tail = list(iter_json_lines(tail_lines))
    meta: dict[str, Any] = {
        "cwd": None, "firstPrompt": None, "model": None, "customTitle": None, "aiTitle": None,
        "agentName": None, "entrypoint": None, "lastTs": None, "lastMessage": None,
        "lastRole": None, "version": None, "sidechainOnly": True, "lastPrompt": None,
    }
    for e in head:
        if meta["cwd"] is None and isinstance(e.get("cwd"), str):
            meta["cwd"] = e["cwd"]
        if meta["entrypoint"] is None and isinstance(e.get("entrypoint"), str):
            meta["entrypoint"] = e["entrypoint"]
        if e.get("type") in ("user", "assistant") and not e.get("isSidechain"):
            meta["sidechainOnly"] = False
        if meta["firstPrompt"] is None and e.get("type") == "user" and not e.get("isMeta") and not e.get("isSidechain"):
            txt = _text_of((e.get("message") or {}).get("content"))
            if txt and not _is_noise_user_text(txt):
                meta["firstPrompt"] = clip(" ".join(txt.split()), 80)
    for e in tail:
        t = e.get("type")
        if isinstance(e.get("cwd"), str):
            meta["cwd"] = e["cwd"]
        if isinstance(e.get("version"), str):
            meta["version"] = e["version"]
        if t == "custom-title" and isinstance(e.get("customTitle"), str):
            meta["customTitle"] = e["customTitle"]
        elif t == "ai-title" and isinstance(e.get("aiTitle"), str):
            meta["aiTitle"] = e["aiTitle"]
        elif t == "agent-name" and isinstance(e.get("agentName"), str):
            meta["agentName"] = e["agentName"]
        elif t == "last-prompt" and isinstance(e.get("lastPrompt"), str):
            meta["lastPrompt"] = e["lastPrompt"]
        if t in ("user", "assistant") and not e.get("isSidechain"):
            meta["sidechainOnly"] = False
            ts = parse_iso(e.get("timestamp"))
            if ts:
                meta["lastTs"] = max(meta["lastTs"] or 0, ts)
            msg = e.get("message") or {}
            if t == "assistant" and isinstance(msg.get("model"), str) and msg["model"] != "<synthetic>":
                meta["model"] = msg["model"]
            txt = _text_of(msg.get("content"))
            if txt and not (t == "user" and (_is_noise_user_text(txt) or e.get("isMeta"))):
                meta["lastMessage"] = clip(" ".join(clean_text(txt).split()), LAST_MESSAGE_CHARS)
                meta["lastRole"] = "assistant" if t == "assistant" else "user"
    _meta_cache.put(str(path), stamp, meta)
    return meta


def _title(meta: dict, cwd: str | None) -> str:
    for key in ("customTitle", "aiTitle", "firstPrompt"):
        if meta.get(key):
            return clip(" ".join(str(meta[key]).split()), 80)
    if meta.get("agentName") and meta["agentName"] not in ("Chat session",):
        return clip(meta["agentName"], 80)
    return os.path.basename(cwd or "") or "Claude Code chat"


def discover_sessions(live: dict[str, dict] | None = None) -> list[dict]:
    root = projects_dir()
    try:
        project_dirs = [p for p in root.iterdir() if p.is_dir()]
    except OSError:
        return []
    cutoff = time.time() - MAX_AGE_DAYS * 86400
    files: list[tuple[float, Path]] = []
    for proj in project_dirs:
        try:
            for f in proj.iterdir():
                if f.suffix == ".jsonl" and f.is_file():
                    m = f.stat().st_mtime
                    if m >= cutoff:
                        files.append((m, f))
        except OSError:
            continue
    files.sort(key=lambda x: x[0], reverse=True)
    live = live_registry() if live is None else live
    # Live chats bypass both age and history-count limits, even after a long tool
    # run with no transcript writes. Discover these independently of recent files.
    known_paths = {p.stem for _, p in files}
    for sid in live:
        if sid not in known_paths:
            p = transcript_path(sid)
            if p is not None:
                files.append((p.stat().st_mtime, p))
    files.sort(key=lambda x: (x[1].stem in live, x[0]), reverse=True)
    out: list[dict] = []
    history_count = 0
    for mtime, f in files:
        native_id = f.stem
        live_entry = live.get(native_id)
        if not live_entry and history_count >= MAX_SESSIONS:
            continue
        try:
            meta = _scan_meta(f)
        except OSError:
            continue
        if not meta or meta["sidechainOnly"]:
            continue
        ep = meta.get("entrypoint") or ""
        if ep.startswith(NON_TERMINAL_ENTRYPOINTS) and not (live_entry and live_entry.get("entrypoint") == "cli"):
            continue
        if not live_entry:
            history_count += 1
        cwd = meta.get("cwd") or (live_entry or {}).get("cwd")
        status, stage = "offline", "Not running · history only"
        evidence = "transcript"
        if live_entry:
            raw = str(live_entry.get("status") or "")
            status = LIVE_STATUS_MAP.get(raw, "unknown")
            stage = None if status != "unknown" else (f"Running · {raw}" if raw else "Running")
            evidence = "claude-session-registry"
        updated = meta.get("lastTs") or mtime
        out.append({
            "id": f"claude:{native_id}",
            "agent": AGENT,
            "nativeId": native_id,
            "title": _title(meta, cwd),
            "cwd": cwd,
            "model": meta.get("model"),
            "status": status,
            "stage": stage,
            "progress": None,
            "updatedAt": utc_iso(updated),
            "managed": False,
            "capabilities": {"send": False, "interrupt": False, "approve": False, "stop": False},
            "lastMessage": meta.get("lastMessage"),
            "unread": 0,
            # private provider metadata (backend tolerates/strips)
            "live": bool(live_entry),
            "canResume": not live_entry,
            "statusEvidence": evidence,
            "livePid": (live_entry or {}).get("pid"),
            "_transcriptPath": str(f),
        })
    listed = {s["nativeId"] for s in out}
    for sid, entry in live.items():
        if sid in listed or str(entry.get("entrypoint") or "").startswith(NON_TERMINAL_ENTRYPOINTS):
            continue
        cwd = entry.get("cwd")
        out.append({
            "id": f"claude:{sid}", "agent": AGENT, "nativeId": sid,
            "title": "New Claude Code chat", "cwd": cwd, "model": None,
            "status": LIVE_STATUS_MAP.get(str(entry.get("status") or ""), "unknown"),
            "stage": None, "progress": None, "updatedAt": entry.get("updatedAt") or utc_iso(),
            "managed": False, "capabilities": {"send": False, "interrupt": False, "approve": False, "stop": False},
            "lastMessage": None, "unread": 0, "live": True, "canResume": False,
            "statusEvidence": "claude-session-registry", "livePid": entry.get("pid"),
        })
    return out


def lookup(native_id: str) -> dict | None:
    """Metadata (title/model/cwd/lastMessage) for a single native session."""
    p = transcript_path(native_id)
    if not p:
        return None
    try:
        meta = _scan_meta(p)
    except OSError:
        return None
    if not meta:
        return None
    return {"title": _title(meta, meta.get("cwd")), "model": meta.get("model"), "cwd": meta.get("cwd"),
            "lastMessage": meta.get("lastMessage"), "path": str(p)}


def _tool_summary(name: str, inp: Any) -> str:
    if isinstance(inp, dict):
        for key in ("command", "file_path", "path", "pattern", "url", "query", "description", "prompt"):
            v = inp.get(key)
            if isinstance(v, str) and v:
                return f"{name}: {clip(' '.join(v.split()), 300)}"
    return name


def read_messages(native_id: str | None, path: str | None, limit: int = 100) -> list[dict]:
    p: Path | None = None
    root = projects_dir()
    if path and is_within(Path(path), root) and path.endswith(".jsonl"):
        p = Path(path)
    elif native_id:
        p = transcript_path(native_id)
    if p is None or not p.is_file():
        return []
    limit = max(1, min(int(limit or 100), 500))
    budget = TAIL_BYTES * 4
    while True:
        lines, at_start = read_tail_lines(p, budget)
        msgs = _parse_messages(lines)
        if len(msgs) >= limit or at_start or budget >= HISTORY_MAX_BYTES:
            return msgs[-limit:]
        budget *= 4


def _parse_messages(lines: list[str], include_sidechain: bool = False) -> list[dict]:
    out: list[dict] = []
    tool_names: dict[str, str] = {}
    for e in iter_json_lines(lines):
        t = e.get("type")
        if t not in ("user", "assistant") or (e.get("isSidechain") and not include_sidechain):
            continue
        msg = e.get("message") or {}
        ts = norm_iso(e.get("timestamp"))
        base_id = str(e.get("uuid") or len(out))
        content = msg.get("content")
        if t == "user":
            if e.get("isMeta"):
                continue
            if isinstance(content, str):
                if _is_noise_user_text(content):
                    continue
                txt = clean_text(content)
                if txt:
                    out.append(_m(base_id, _user_role(txt), txt, ts))
                continue
            if not isinstance(content, list):
                continue
            for i, b in enumerate(content):
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text":
                    if _is_noise_user_text(b.get("text") or ""):
                        continue
                    txt = clean_text(b.get("text"))
                    if txt:
                        out.append(_m(f"{base_id}:{i}", _user_role(txt), txt, ts))
                elif b.get("type") == "tool_result":
                    res = b.get("content")
                    txt = res if isinstance(res, str) else _text_of(res)
                    name = tool_names.get(str(b.get("tool_use_id")), None)
                    prefix = "Error: " if b.get("is_error") else ""
                    out.append(_m(f"{base_id}:{i}", "tool", prefix + clean_text(txt, MAX_TOOL_CHARS), ts, name))
        else:
            if not isinstance(content, list):
                continue
            for i, b in enumerate(content):
                if not isinstance(b, dict):
                    continue
                bt = b.get("type")
                if bt == "text":
                    txt = clean_text(b.get("text"))
                    if txt:
                        out.append(_m(f"{base_id}:{i}", "assistant", txt, ts))
                elif bt == "tool_use":
                    name = str(b.get("name") or "tool")
                    tool_names[str(b.get("id"))] = name
                    out.append(_m(f"{base_id}:{i}", "tool", clean_text(_tool_summary(name, b.get("input")), MAX_TOOL_CHARS), ts, name))
    return out


def _user_role(text: str) -> str:
    """Claude records interrupts as user text ("[Request interrupted by user…]"); show them as system."""
    return "system" if text.startswith("[Request interrupted by user") else "user"


def _m(mid: str, role: str, text: str, ts: str, tool: str | None = None) -> dict:
    return {"id": mid, "role": role, "text": text, "timestamp": ts, "toolName": tool}
