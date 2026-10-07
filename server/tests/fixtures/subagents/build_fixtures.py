"""Build realistic Claude Code + Codex homes containing real-shaped subagents.

Shapes copied (trimmed, redacted) from actual files on the dev Mac:
- Claude: ``projects/<slug>/<parent>.jsonl`` with ``Agent`` tool_use, async
  ``toolUseResult`` and ``<task-notification>`` user records; children at
  ``projects/<slug>/<parent>/subagents/agent-<id>.jsonl`` + ``.meta.json``.
- Codex: ``state_5.sqlite`` ``threads`` + ``thread_spawn_edges`` (edge status
  stays ``open`` even after the child finished) and child rollouts whose final
  ``event_msg`` is ``task_complete`` / ``error`` or still in progress.

Usage: ``build(root: Path, now: float) -> dict`` returns the IDs used.
"""

from __future__ import annotations

import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path

PROJECT_CWD = "/Users/test/proj"
CLAUDE_SLUG = "-Users-test-proj"
CLAUDE_PARENT = "11111111-2222-4333-8444-555555555555"
CLAUDE_CHILDREN = {
    # agentId: (description, agentType, toolUseId, outcome)
    "a0000000000000001": ("Review backend auth", "general-purpose", "toolu_fixture_01", "completed"),
    "a0000000000000002": ("Build Android list", "general-purpose", "toolu_fixture_02", "failed"),
    "a0000000000000003": ("Search model docs", "Explore", "toolu_fixture_03", "working"),
}
CODEX_PARENT = "01a10000-0000-7000-8000-000000000001"
CODEX_CHILDREN = {
    # threadId: (nickname, role, agent_path, outcome)
    "01a10000-0000-7000-8000-0000000000a1": ("Newton", None, "/root/shell", "completed"),
    "01a10000-0000-7000-8000-0000000000a2": ("Parfit", "reviewer", "/root/services", "working"),
    "01a10000-0000-7000-8000-0000000000a3": ("Maxwell", None, "/root/research", "error"),
}
# Codex thread whose source is a guardian subagent without a spawn edge: must NOT
# be attached to any parent (no invented relationship).
CODEX_GUARDIAN = "01a10000-0000-7000-8000-0000000000ff"


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _jsonl(path: Path, rows: list[dict], mtime: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))
    os.utime(path, (mtime, mtime))


def _claude_base(ts: float, session: str, sidechain: bool, agent_id: str | None = None) -> dict:
    base = {"isSidechain": sidechain, "userType": "external", "entrypoint": "cli", "cwd": PROJECT_CWD,
            "sessionId": session, "version": "2.1.285", "gitBranch": "main", "timestamp": iso(ts)}
    if agent_id:
        base["agentId"] = agent_id
    return base


def build_claude(home: Path, now: float) -> None:
    proj = home / "projects" / CLAUDE_SLUG
    t0 = now - 1800
    parent: list[dict] = [
        {**_claude_base(t0, CLAUDE_PARENT, False), "type": "user", "uuid": "p-u1",
         "message": {"role": "user", "content": "Split the work across three agents and report back."}},
    ]
    for i, (aid, (desc, kind, tuid, outcome)) in enumerate(CLAUDE_CHILDREN.items()):
        ts = t0 + 10 + i
        parent.append({**_claude_base(ts, CLAUDE_PARENT, False), "type": "assistant", "uuid": f"p-a{i}",
                       "message": {"model": "claude-opus-5-5", "role": "assistant", "stop_reason": "tool_use",
                                   "content": [{"type": "tool_use", "id": tuid, "name": "Agent",
                                                "input": {"description": desc, "subagent_type": kind,
                                                          "prompt": f"You are the {desc} agent.", "run_in_background": True}}]}})
        parent.append({**_claude_base(ts + 0.5, CLAUDE_PARENT, False), "type": "user", "uuid": f"p-r{i}",
                       "message": {"role": "user", "content": [{"tool_use_id": tuid, "type": "tool_result",
                                   "content": [{"type": "text", "text": f"Async agent launched successfully.\nagentId: {aid}"}]}]},
                       "toolUseResult": {"isAsync": True, "status": "async_launched", "agentId": aid, "description": desc}})
        child_rows = [
            {**_claude_base(ts + 1, CLAUDE_PARENT, True, aid), "type": "user", "uuid": f"{aid}-u0", "parentUuid": None,
             "message": {"role": "user", "content": f"You are the {desc} agent."}},
            {**_claude_base(ts + 5, CLAUDE_PARENT, True, aid), "type": "assistant", "uuid": f"{aid}-a1",
             "message": {"model": "claude-opus-5-5", "role": "assistant", "stop_reason": "tool_use",
                         "content": [{"type": "text", "text": "Reading the relevant files."},
                                     {"type": "tool_use", "id": f"{aid}-t1", "name": "Read", "input": {"file_path": f"{PROJECT_CWD}/README.md"}}]}},
            {**_claude_base(ts + 6, CLAUDE_PARENT, True, aid), "type": "user", "uuid": f"{aid}-u1",
             "message": {"role": "user", "content": [{"tool_use_id": f"{aid}-t1", "type": "tool_result", "content": "# proj"}]}},
        ]
        if outcome == "working":
            child_rows.append({**_claude_base(now - 5, CLAUDE_PARENT, True, aid), "type": "assistant", "uuid": f"{aid}-a2",
                               "message": {"model": "claude-opus-5-5", "role": "assistant", "stop_reason": "tool_use",
                                           "content": [{"type": "tool_use", "id": f"{aid}-t2", "name": "Grep", "input": {"pattern": "model"}}]}})
            child_mtime = now - 5
        else:
            final = "Auth review done: 2 issues fixed." if outcome == "completed" else "Now I'll write the list screen."
            child_rows.append({**_claude_base(t0 + 600 + i, CLAUDE_PARENT, True, aid), "type": "assistant", "uuid": f"{aid}-a2",
                               "message": {"model": "claude-opus-5-5", "role": "assistant", "stop_reason": "end_turn",
                                           "content": [{"type": "text", "text": final}]}})
            child_mtime = t0 + 600 + i
            summary = f'Agent "{desc}" ' + ("completed" if outcome == "completed" else "failed: Agent stalled: no progress for 600s")
            body = (f"<task-notification>\n<task-id>{aid}</task-id>\n<tool-use-id>{tuid}</tool-use-id>\n"
                    f"<status>{outcome}</status>\n<summary>{summary}</summary>\n<result>{final}</result>\n</task-notification>")
            parent.append({**_claude_base(t0 + 601 + i, CLAUDE_PARENT, False), "type": "user", "uuid": f"p-n{i}",
                           "origin": {"kind": "task-notification", "producer": "session-task"},
                           "message": {"role": "user", "content": body}})
        sub = proj / CLAUDE_PARENT / "subagents"
        _jsonl(sub / f"agent-{aid}.jsonl", child_rows, child_mtime)
        meta = {"agentType": kind, "description": desc, "toolUseId": tuid, "spawnDepth": 1,
                "requestShape": "background", "requestNonInteractive": True}
        (sub / f"agent-{aid}.meta.json").write_text(json.dumps(meta))
    parent.append({**_claude_base(now - 30, CLAUDE_PARENT, False), "type": "assistant", "uuid": "p-last",
                   "message": {"model": "claude-opus-5-5", "role": "assistant", "stop_reason": "end_turn",
                               "content": [{"type": "text", "text": "Two agents finished; one is still searching."}]}})
    parent.sort(key=lambda r: r["timestamp"])
    _jsonl(proj / f"{CLAUDE_PARENT}.jsonl", parent, now - 30)


def _rollout(path: Path, thread: str, parent: str | None, source: dict | str, events: list[tuple[float, dict]], mtime: float) -> None:
    rows = [{"timestamp": iso(events[0][0] - 1), "type": "session_meta",
             "payload": {"session_id": parent or thread, "id": thread, "parent_thread_id": parent,
                         "timestamp": iso(events[0][0] - 1), "cwd": PROJECT_CWD, "originator": "codex-tui",
                         "cli_version": "0.154.0", "source": source}}]
    rows += [{"timestamp": iso(ts), "type": kind_payload.pop("_type", "event_msg"), "payload": kind_payload}
             for ts, kind_payload in events]
    _jsonl(path, rows, mtime)


def build_codex(home: Path, now: float) -> None:
    sessions = home / "sessions" / "2026" / "10" / "07"
    db = sqlite3.connect(home / "state_5.sqlite")
    db.executescript("""
    CREATE TABLE threads (id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL, created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL, source TEXT NOT NULL, model_provider TEXT NOT NULL, cwd TEXT NOT NULL,
      title TEXT NOT NULL, sandbox_policy TEXT NOT NULL, approval_mode TEXT NOT NULL,
      tokens_used INTEGER NOT NULL DEFAULT 0, has_user_event INTEGER NOT NULL DEFAULT 0,
      archived INTEGER NOT NULL DEFAULT 0, archived_at INTEGER, git_sha TEXT, git_branch TEXT, git_origin_url TEXT,
      cli_version TEXT NOT NULL DEFAULT '', first_user_message TEXT NOT NULL DEFAULT '', agent_nickname TEXT,
      agent_role TEXT, memory_mode TEXT NOT NULL DEFAULT 'enabled', model TEXT, reasoning_effort TEXT,
      agent_path TEXT, created_at_ms INTEGER, updated_at_ms INTEGER, thread_source TEXT,
      preview TEXT NOT NULL DEFAULT '', recency_at INTEGER NOT NULL DEFAULT 0, recency_at_ms INTEGER NOT NULL DEFAULT 0,
      history_mode TEXT NOT NULL DEFAULT 'legacy', name TEXT);
    CREATE TABLE thread_spawn_edges (parent_thread_id TEXT NOT NULL, child_thread_id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL);
    """)

    def add(tid: str, source: str, title: str, first: str, updated: float, nick=None, role=None, path=None, tsrc=None) -> Path:
        rp = sessions / f"rollout-2026-10-07T10-00-00-{tid}.jsonl"
        db.execute("INSERT INTO threads (id,rollout_path,created_at,updated_at,source,model_provider,cwd,title,sandbox_policy,"
                   "approval_mode,cli_version,first_user_message,agent_nickname,agent_role,model,reasoning_effort,agent_path,"
                   "created_at_ms,updated_at_ms,thread_source,preview,recency_at,recency_at_ms) VALUES "
                   "(?,?,?,?,?,'openai',?,?,'workspace-write','on-request','0.154.0',?,?,?,'gpt-6.1-sol','medium',?,?,?,?,?,?,?)",
                   (tid, str(rp), int(now - 3600), int(updated), source, PROJECT_CWD, title, first, nick, role, path,
                    int((now - 3600) * 1000), int(updated * 1000), tsrc, first[:80], int(updated), int(updated * 1000)))
        return rp

    t0 = now - 1200
    prp = add(CODEX_PARENT, "cli", "Ship the release", "Ship the release with three helpers", now - 20)
    _rollout(prp, CODEX_PARENT, None, "cli", [
        (t0, {"_type": "response_item", "type": "message", "role": "user",
              "content": [{"type": "input_text", "text": "Ship the release with three helpers"}]}),
        (t0 + 2, {"type": "task_started", "turn_id": "turn-p1"}),
        (now - 20, {"_type": "response_item", "type": "message", "role": "assistant",
                    "content": [{"type": "output_text", "text": "Waiting for Parfit."}]}),
    ], now - 20)
    for i, (tid, (nick, role, apath, outcome)) in enumerate(CODEX_CHILDREN.items()):
        src = {"subagent": {"thread_spawn": {"parent_thread_id": CODEX_PARENT, "depth": 1, "agent_path": apath,
                                             "agent_nickname": nick, "agent_role": role}}}
        last = now - 8 if outcome == "working" else t0 + 300 + i
        rp = add(tid, json.dumps(src), "", f"Task for {nick}: handle {apath}", last, nick, role, apath, "subagent")
        db.execute("INSERT INTO thread_spawn_edges VALUES (?,?, 'open')", (CODEX_PARENT, tid))
        events: list[tuple[float, dict]] = [
            (t0 + 10 + i, {"_type": "response_item", "type": "message", "role": "user",
                           "content": [{"type": "input_text", "text": f"Task for {nick}: handle {apath}"}]}),
            (t0 + 11 + i, {"type": "task_started", "turn_id": f"turn-{i}"}),
            (t0 + 20 + i, {"type": "item_completed", "thread_id": tid, "turn_id": f"turn-{i}",
                           "item": {"type": "AgentMessage", "id": f"msg-{i}",
                                    "content": [{"type": "Text", "text": f"{nick}: checking {apath}."}]}}),
            (t0 + 21 + i, {"type": "item_completed", "thread_id": tid, "turn_id": f"turn-{i}",
                           "item": {"type": "CommandExecution", "id": f"exec-{i}", "command": ["/bin/zsh", "-lc", "ls -la"],
                                    "cwd": PROJECT_CWD, "exit_code": 0}}),
        ]
        if outcome == "completed":
            events.append((last, {"type": "task_complete", "turn_id": f"turn-{i}", "last_agent_message": f"{nick} finished the shell."}))
        elif outcome == "error":
            events.append((last, {"type": "error", "message": "stream disconnected before completion"}))
        else:
            events.append((last, {"type": "item_completed", "thread_id": tid, "turn_id": f"turn-{i}",
                                  "item": {"type": "CommandExecution", "id": f"exec-{i}b",
                                           "command": ["/bin/zsh", "-lc", "pytest -q"], "cwd": PROJECT_CWD, "exit_code": 0}}))
        _rollout(rp, tid, CODEX_PARENT, src, events, last)
    grp = add(CODEX_GUARDIAN, json.dumps({"subagent": {"other": "guardian"}}), "", "guardian check", t0, tsrc="subagent")
    _rollout(grp, CODEX_GUARDIAN, None, {"subagent": {"other": "guardian"}},
             [(t0, {"type": "task_complete", "turn_id": "g", "last_agent_message": "ok"})], t0)
    db.commit()
    db.close()


def build(root: Path, now: float | None = None) -> dict:
    now = time.time() if now is None else now
    claude_home, codex_home = root / "claude", root / "codex"
    claude_home.mkdir(parents=True, exist_ok=True)
    codex_home.mkdir(parents=True, exist_ok=True)
    build_claude(claude_home, now)
    build_codex(codex_home, now)
    return {"claudeHome": claude_home, "codexHome": codex_home, "claudeParent": CLAUDE_PARENT,
            "claudeChildren": CLAUDE_CHILDREN, "codexParent": CODEX_PARENT, "codexChildren": CODEX_CHILDREN,
            "codexGuardian": CODEX_GUARDIAN, "cwd": PROJECT_CWD}


if __name__ == "__main__":
    import sys

    out = build(Path(sys.argv[1]))
    print(json.dumps({k: str(v) for k, v in out.items()}, indent=1))
