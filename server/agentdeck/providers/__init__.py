"""Provider boundary used by the backend (see docs/API.md).

- ``discover_sessions()``: bounded, read-only listing of existing Claude Code and
  Codex terminal chats plus their real subagents, with honest live-state evidence
  (``statusEvidence``) and a ``projectRoot`` grouping key.
- ``read_messages(session, limit)``: role-labelled history from native transcripts
  (a subagent reads its own transcript).
- ``discover_models(refresh)``: live model lists from the installed CLIs.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Any

from . import claude, codex, subagents
from .models import discover_models  # noqa: F401  (re-export)

__all__ = ["discover_sessions", "read_messages", "discover_models", "lookup_native", "project_root"]

_root_cache: dict[str, str] = {}
_root_lock = threading.Lock()


def project_root(cwd: Any) -> str | None:
    """Git top-level of ``cwd`` when cheaply known (walk up for ``.git``), else realpath(cwd)."""
    if not isinstance(cwd, str) or not cwd:
        return None
    with _root_lock:
        hit = _root_cache.get(cwd)
    if hit is not None:
        return hit
    try:
        real = os.path.realpath(cwd)
    except OSError:
        real = cwd
    root = real
    p = Path(real)
    home = Path.home()
    for cand in [p, *p.parents][:12]:
        if cand == home or cand == cand.parent:
            break
        try:
            if (cand / ".git").exists():
                root = str(cand)
                break
        except OSError:
            break
    with _root_lock:
        if len(_root_cache) > 2048:
            _root_cache.clear()
        _root_cache[cwd] = root
    return root


def discover_sessions() -> list[dict]:
    parents: list[dict] = []
    for mod in (claude, codex):
        try:
            parents.extend(mod.discover_sessions())
        except Exception:  # noqa: BLE001 - one broken source must not hide the other
            continue
    try:
        children = subagents.discover(parents)
    except Exception:  # noqa: BLE001
        children = []
    for s in parents:
        s.setdefault("parentSessionId", None)
        s.setdefault("agentName", None)
        s.setdefault("agentRole", None)
        s.setdefault("isSubagent", False)
        s.setdefault("task", None)
    sessions = parents + children
    for s in sessions:
        s["projectRoot"] = project_root(s.get("cwd"))
    parents.sort(key=lambda s: s.get("updatedAt") or "", reverse=True)
    return parents + children


def read_messages(session: dict, limit: int = 100) -> list[dict]:
    agent = session.get("agent")
    native = session.get("nativeId")
    if session.get("isSubagent") or session.get("_subagent"):
        if agent == "claude" and not session.get("_transcriptPath") and isinstance(native, str):
            found = claude.subagent_transcript(native)
            if found:
                session = dict(session, _transcriptPath=str(found))
        return subagents.read_messages(session, limit)
    if agent == "claude":
        return claude.read_messages(native, session.get("_transcriptPath"), limit)
    if agent == "codex":
        return codex.read_messages(native, session.get("_rolloutPath"), limit)
    return []


def lookup_native(agent: str, native_id: str) -> dict[str, Any] | None:
    if agent == "claude":
        return claude.lookup(native_id)
    if agent == "codex":
        return codex.lookup(native_id)
    return None
