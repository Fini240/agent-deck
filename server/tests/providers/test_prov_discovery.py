"""Transcript discovery/history, subagents and live model discovery against realistic fixtures."""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "fixtures" / "subagents"))
import build_fixtures  # noqa: E402

from agentdeck import providers  # noqa: E402
from agentdeck.providers import claude, codex, models  # noqa: E402

NOW = time.time()


def iso(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts)) + ".000Z"


@pytest.fixture
def homes(tmp_path, monkeypatch):
    ch, xh = tmp_path / "claude", tmp_path / "codex"
    monkeypatch.setenv("AGENTDECK_CLAUDE_HOME", str(ch))
    monkeypatch.setenv("AGENTDECK_CODEX_HOME", str(xh))
    monkeypatch.setenv("AGENTDECK_CODEX_CONTROL_SOCK", str(tmp_path / "none.sock"))
    monkeypatch.setenv("AGENTDECK_HOME", str(tmp_path / "ad"))
    return ch, xh


# ----------------------------------------------------------------- Claude transcripts

CL_ID = "aaaaaaaa-1111-4222-8333-444444444444"
CL_SDK = "bbbbbbbb-1111-4222-8333-444444444444"


def write_claude(ch: Path) -> Path:
    proj = ch / "projects" / "-Users-test-app"
    proj.mkdir(parents=True)
    base = {"cwd": "/Users/test/app", "sessionId": CL_ID, "entrypoint": "cli", "version": "2.1.290", "userType": "external"}
    rows = [
        {**base, "type": "user", "uuid": "u0", "isMeta": True, "timestamp": iso(NOW - 300),
         "message": {"role": "user", "content": "<local-command-caveat>Caveat: ignore</local-command-caveat>"}},
        {**base, "type": "user", "uuid": "u1", "timestamp": iso(NOW - 290),
         "message": {"role": "user", "content": "Fix the login bug. token=sk-ant-api03-abcdefghijklmnopqrstuv"}},
        {**base, "type": "assistant", "uuid": "a1", "timestamp": iso(NOW - 280),
         "message": {"role": "assistant", "model": "claude-opus-5-5", "content": [
             {"type": "thinking", "thinking": "secret thoughts"},
             {"type": "text", "text": "Looking at auth.py"},
             {"type": "tool_use", "id": "toolu_1", "name": "Bash", "input": {"command": "pytest -q tests/auth"}}]}},
        {**base, "type": "user", "uuid": "u2", "timestamp": iso(NOW - 270),
         "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "is_error": True,
                                                  "content": "1 failed"}]}},
        {**base, "type": "assistant", "uuid": "s1", "isSidechain": True, "timestamp": iso(NOW - 265),
         "message": {"role": "assistant", "model": "claude-haiku-4-5-20251001", "content": [{"type": "text", "text": "sidechain"}]}},
        {"type": "ai-title", "aiTitle": "Fix login bug", "sessionId": CL_ID},
        {**base, "type": "assistant", "uuid": "a2", "timestamp": iso(NOW - 260),
         "message": {"role": "assistant", "model": "claude-opus-5-5", "content": [{"type": "text", "text": "Fixed and tests pass."}]}},
        {"type": "custom-title", "customTitle": "Login fix (renamed)", "sessionId": CL_ID},
    ]
    p = proj / f"{CL_ID}.jsonl"
    p.write_text("".join(json.dumps(r) + "\n" for r in rows))
    # a programmatic -p run (sdk) must not be listed as a terminal chat
    (proj / f"{CL_SDK}.jsonl").write_text(json.dumps({**base, "sessionId": CL_SDK, "entrypoint": "sdk-cli", "type": "user",
                                                      "timestamp": iso(NOW), "message": {"role": "user", "content": "x"}}) + "\n")
    # credential-looking files beside transcripts are never opened
    (ch / "sessions").mkdir(parents=True, exist_ok=True)
    (ch / "sessions" / "123.abc.key").write_text("SECRET")
    return p


def test_claude_discovery_titles_models_status(homes):
    ch, _ = homes
    write_claude(ch)
    rows = claude.discover_sessions(live={})
    assert {r["nativeId"] for r in rows} == {CL_ID, CL_SDK}
    r = next(r for r in rows if r["nativeId"] == CL_ID)
    assert r["id"] == f"claude:{CL_ID}" and r["title"] == "Login fix (renamed)"
    assert r["model"] == "claude-opus-5-5" and r["cwd"] == "/Users/test/app"
    assert r["status"] == "offline" and r["canResume"] and not r["live"]
    assert r["capabilities"] == {"send": False, "interrupt": False, "approve": False, "stop": False}
    assert r["lastMessage"] == "Fixed and tests pass."


def test_claude_live_registry_drives_status_never_guesses(homes):
    ch, _ = homes
    write_claude(ch)
    (ch / "sessions" / f"{os.getpid()}.json").write_text(json.dumps({"pid": os.getpid(), "sessionId": CL_ID, "status": "busy",
                                                                     "cwd": "/Users/test/app", "entrypoint": "cli"}))
    (ch / "sessions" / "999999.json").write_text(json.dumps({"pid": 999999, "sessionId": CL_ID, "status": "idle"}))  # dead pid
    r = next(r for r in claude.discover_sessions() if r["nativeId"] == CL_ID)
    assert r["status"] == "working" and r["live"] and not r["canResume"]
    assert r["statusEvidence"] == "claude-session-registry"
    (ch / "sessions" / f"{os.getpid()}.json").write_text(json.dumps({"pid": os.getpid(), "sessionId": CL_ID, "status": "shell"}))
    r = next(r for r in claude.discover_sessions() if r["nativeId"] == CL_ID)
    assert r["status"] == "unknown"  # unknown native state stays unknown


def test_claude_messages_roles_tools_redaction(homes):
    ch, _ = homes
    write_claude(ch)
    s = next(s for s in providers.discover_sessions() if s["nativeId"] == CL_ID)
    msgs = providers.read_messages(s, 50)
    assert [(m["role"], m["toolName"]) for m in msgs] == [
        ("user", None), ("assistant", None), ("tool", "Bash"), ("tool", "Bash"), ("assistant", None)]
    assert "sk-ant" not in msgs[0]["text"] and "[redacted]" in msgs[0]["text"]
    assert msgs[2]["text"] == "Bash: pytest -q tests/auth"
    assert msgs[3]["text"] == "Error: 1 failed"
    assert all("secret thoughts" not in m["text"] and m["text"] != "sidechain" for m in msgs)
    assert providers.read_messages(s, 2) == msgs[-2:]
    # path outside the Claude projects dir is ignored
    assert providers.read_messages({**s, "_transcriptPath": "/etc/passwd", "nativeId": None}, 5) == []


# ----------------------------------------------------------------- Codex threads

def write_codex(xh: Path) -> dict:
    xh.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(xh / "state_5.sqlite")
    db.executescript("""CREATE TABLE threads (id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL, created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL, source TEXT NOT NULL, cwd TEXT NOT NULL, title TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
      first_user_message TEXT NOT NULL DEFAULT '', model TEXT, created_at_ms INTEGER, updated_at_ms INTEGER, thread_source TEXT,
      preview TEXT NOT NULL DEFAULT '', name TEXT, originator TEXT);
      CREATE TABLE thread_spawn_edges (parent_thread_id TEXT NOT NULL, child_thread_id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL);""")
    ids = {"tui": "01a11310-d601-7430-96c6-81bad13d5290", "legacy": "01a0c928-a117-7740-8e95-0a1487af871c",
           "desktop": "01a10e20-de77-7e53-9ba8-5eaebee34105", "guardian": "01a10000-0000-7000-8000-00000000beef",
           "archived": "01a10000-0000-7000-8000-00000000dead"}
    rows = [("tui", "vscode", "user", "codex-tui", 0), ("legacy", "cli", None, None, 0),
            ("desktop", "vscode", "user", "Codex Desktop", 0),
            ("guardian", '{"subagent":{"other":"guardian"}}', "guardian_review", "codex-tui", 0),
            ("archived", "cli", None, None, 1)]
    sess = xh / "sessions" / "2026" / "10" / "07"
    sess.mkdir(parents=True)
    for key, src, tsrc, orig, arch in rows:
        tid = ids[key]
        rp = sess / f"rollout-2026-10-07T00-00-00-{tid}.jsonl"
        db.execute("INSERT INTO threads VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (tid, str(rp), int(NOW - 600), int(NOW - 60), src, "/Users/test/app", f"{key} title", arch,
                    "# AGENTS.md instructions for /Users/test" if key == "legacy" else f"{key} prompt", "gpt-6.1-sol",
                    int((NOW - 600) * 1000), int((NOW - 60) * 1000), tsrc, "", None, orig))
        ev = [
            {"timestamp": iso(NOW - 600), "type": "session_meta", "payload": {"id": tid, "cwd": "/Users/test/app"}},
            {"timestamp": iso(NOW - 599), "type": "response_item", "payload": {"type": "message", "role": "developer",
                                                                                 "content": [{"type": "input_text", "text": "system rules"}]}},
            {"timestamp": iso(NOW - 598), "type": "response_item", "payload": {"type": "message", "role": "user",
                                                                                 "content": [{"type": "input_text", "text": "# AGENTS.md instructions for /Users/test\n..."}]}},
            {"timestamp": iso(NOW - 597), "type": "turn_context", "payload": {"model": "gpt-6.1-sol", "cwd": "/Users/test/app"}},
            {"timestamp": iso(NOW - 596), "type": "event_msg", "payload": {"type": "task_started", "turn_id": "t1"}},
            {"timestamp": iso(NOW - 596), "type": "response_item", "payload": {"type": "message", "role": "user",
                                                                                 "content": [{"type": "input_text", "text": "check the trading bots"}]}},
            {"timestamp": iso(NOW - 596), "type": "event_msg", "payload": {"type": "item_completed", "turn_id": "t1",
                "item": {"type": "UserMessage", "id": "i1", "content": [{"type": "text", "text": "check the trading bots"}]}}},
            {"timestamp": iso(NOW - 590), "type": "event_msg", "payload": {"type": "item_completed", "turn_id": "t1",
                "item": {"type": "CommandExecution", "id": "i2", "command": ["/bin/zsh", "-lc", "docker ps"], "exit_code": 1}}},
            {"timestamp": iso(NOW - 585), "type": "event_msg", "payload": {"type": "item_completed", "turn_id": "t1",
                "item": {"type": "AgentMessage", "id": "i3", "content": [{"type": "text", "text": "Both bots are up."}], "phase": "final"}}},
            {"timestamp": iso(NOW - 585), "type": "event_msg", "payload": {"type": "agent_message", "message": "Both bots are up."}},
            {"timestamp": iso(NOW - 584), "type": "event_msg", "payload": {"type": "task_complete", "turn_id": "t1",
                                                                             "last_agent_message": "Both bots are up."}},
        ]
        rp.write_text("".join(json.dumps(e) + "\n" for e in ev))
    db.commit()
    db.close()
    return ids


def test_codex_discovery_filters_terminal_threads(homes):
    _, xh = homes
    ids = write_codex(xh)
    rows = codex.discover_sessions()
    got = {r["nativeId"] for r in rows}
    assert got == {ids["tui"], ids["legacy"]}  # desktop, guardian, archived excluded
    r = next(r for r in rows if r["nativeId"] == ids["tui"])
    assert r["model"] == "gpt-6.1-sol" and r["title"] == "tui title"
    assert r["status"] == "offline" and r["statusEvidence"] == "state-db"
    assert r["lastMessage"] == "Both bots are up."


def test_codex_daemon_status_mapping(homes, monkeypatch):
    _, xh = homes
    ids = write_codex(xh)
    from agentdeck.providers import codex_daemon

    monkeypatch.setattr(codex_daemon, "loaded_threads", lambda *a, **k: {
        ids["tui"]: {"status": "active", "activeFlags": ["waitingOnApproval"], "originator": "codex-tui", "cwd": "/x"},
        "01a10000-0000-7000-8000-00000000f00d": {"status": "idle", "activeFlags": [], "originator": "codex-tui", "cwd": "/y"},
        "01a10000-0000-7000-8000-00000000d00d": {"status": "active", "activeFlags": [], "originator": "Codex Desktop"},
    })
    rows = {r["nativeId"]: r for r in codex.discover_sessions()}
    assert rows[ids["tui"]]["status"] == "needs_input" and rows[ids["tui"]]["live"] and not rows[ids["tui"]]["canResume"]
    assert rows["01a10000-0000-7000-8000-00000000f00d"]["title"] == "New Codex chat"
    assert "01a10000-0000-7000-8000-00000000d00d" not in rows  # desktop-app thread is not a terminal chat
    assert codex.daemon_status({"status": "active", "activeFlags": []}) == ("working", None)
    assert codex.daemon_status({"status": "systemError"})[0] == "error"


def test_codex_messages_dedupe_and_skip_injected(homes):
    _, xh = homes
    ids = write_codex(xh)
    s = next(r for r in providers.discover_sessions() if r["nativeId"] == ids["tui"])
    msgs = providers.read_messages(s, 20)
    assert [(m["role"], m["text"]) for m in msgs] == [
        ("user", "check the trading bots"), ("tool", "$ /bin/zsh -lc docker ps (exit 1)"), ("assistant", "Both bots are up.")]


# ----------------------------------------------------------------- subagents

def test_subagents_grouped_with_honest_status(homes, tmp_path, monkeypatch):
    ids = build_fixtures.build(tmp_path / "fx", NOW)
    monkeypatch.setenv("AGENTDECK_CLAUDE_HOME", str(ids["claudeHome"]))
    monkeypatch.setenv("AGENTDECK_CODEX_HOME", str(ids["codexHome"]))
    rows = providers.discover_sessions()
    by = {r["id"]: r for r in rows}
    cparent = f"claude:{ids['claudeParent']}"
    xparent = f"codex:{ids['codexParent']}"
    assert cparent in by and xparent in by and not by[cparent]["isSubagent"]
    kids = [r for r in rows if r["isSubagent"]]
    assert len(kids) == 6
    status = {r["agentName"]: r["status"] for r in kids}
    assert status == {"Review backend auth": "completed", "Build Android list": "error", "Search model docs": "working",
                      "Newton": "completed", "Parfit": "working", "Maxwell": "error"}
    for r in kids:
        assert r["parentSessionId"] == (cparent if r["agent"] == "claude" else xparent)
        assert r["capabilities"] == {"send": False, "interrupt": False, "approve": False, "stop": False}
        assert r["projectRoot"] == "/Users/test/proj" and r["task"]
        assert not r["canResume"]
    assert {r["agentRole"] for r in kids if r["agent"] == "claude"} == {"general-purpose", "Explore"}
    assert next(r for r in kids if r["agentName"] == "Parfit")["agentRole"] == "reviewer"
    # the guardian thread has no spawn edge: it is neither a child nor a top-level chat
    assert all(ids["codexGuardian"] not in r["id"] for r in rows)
    # each child reads its OWN transcript
    for r in kids:
        msgs = providers.read_messages(r, 50)
        assert msgs and msgs[0]["role"] == "user"
    maxwell = next(r for r in kids if r["agentName"] == "Maxwell")
    assert providers.read_messages(maxwell, 50)[-1]["text"].startswith("Error: stream disconnected")
    claude_kid = next(r for r in kids if r["agentName"] == "Search model docs")
    lean = {k: v for k, v in claude_kid.items() if not k.startswith("_")}  # transcript path stripped
    assert providers.read_messages(lean, 50) == providers.read_messages(claude_kid, 50)


def test_codex_forked_child_hides_parent_context_and_shows_extension_tools():
    # Shape of a real forked Codex child rollout: own meta, parent's meta + compacted
    # context ending in the parent's task_complete, then the child's own turn.
    ev = lambda ts, typ, pl: json.dumps({"timestamp": ts, "type": typ, "payload": pl})
    lines = [
        ev("2026-10-07T10:00:00Z", "session_meta", {"id": "child-1", "parent_thread_id": "parent-1"}),
        ev("2026-10-07T10:00:00Z", "session_meta", {"id": "parent-1"}),
        ev("2026-10-07T10:00:00Z", "compacted", {"message": "parent history"}),
        ev("2026-10-07T10:00:00Z", "event_msg", {"type": "task_complete", "last_agent_message": "PARENT SAID THIS"}),
        ev("2026-10-07T10:00:01Z", "event_msg", {"type": "task_started", "turn_id": "c1"}),
        ev("2026-10-07T10:00:01Z", "response_item", {"type": "message", "role": "user",
                                                       "content": [{"type": "input_text", "text": "research competitors"}]}),
        ev("2026-10-07T10:00:02Z", "event_msg", {"type": "item_completed", "item": {
            "type": "Extension", "id": "x1", "kind": "web.search", "query": "agent deck apps", "results": []}}),
        ev("2026-10-07T10:00:03Z", "event_msg", {"type": "item_completed", "item": {
            "type": "SubAgentActivity", "id": "s1", "kind": "spawn", "agent_path": "/root/helper"}}),
        ev("2026-10-07T10:00:04Z", "event_msg", {"type": "item_completed", "item": {
            "type": "AgentMessage", "id": "m1", "content": [{"type": "Text", "text": "Found three."}]}}),
    ]
    msgs = codex.parse_rollout(lines)
    assert [(m["role"], m["text"], m["toolName"]) for m in msgs] == [
        ("user", "research competitors", None), ("tool", "web.search: agent deck apps", "web.search"),
        ("tool", "Subagent /root/helper", "subagent"), ("assistant", "Found three.", None)]
    # a normal (non-forked) rollout keeps everything
    assert codex.parse_rollout([lines[0]] + lines[3:])[0]["text"] == "PARENT SAID THIS"


# ----------------------------------------------------------------- models

FAKE_CLAUDE = r'''#!{py}
import json, sys
argv = sys.argv[1:]
if argv == ["--version"]:
    print("2.1.290 (Claude Code)"); sys.exit(0)
assert "--setting-sources" in argv and "--no-session-persistence" in argv and "--strict-mcp-config" in argv
open({log!r}, "a").write(json.dumps(argv) + "\n")
for line in sys.stdin:
    m = json.loads(line)
    open({log!r}, "a").write(line)
    if m.get("type") == "user":
        print(json.dumps({{"type": "result", "subtype": "error", "inference": True}})); sys.stdout.flush()
    if m.get("type") == "control_request" and m["request"]["subtype"] == "initialize":
        print(json.dumps({{"type": "system", "subtype": "hook_started"}}))
        print(json.dumps({{"type": "control_response", "response": {{"subtype": "success", "request_id": m["request_id"],
            "response": {{"account": {{"email": "never@leak.example"}}, "models": [
              {{"value": "default", "resolvedModel": "claude-zeta-9-1", "displayName": "Default (recommended)", "description": "Zeta 9.1"}},
              {{"value": "claude-zeta-9-1-20991231", "displayName": "Zeta 9.1 dated", "supportedEffortLevels": ["low", "max"]}}]}}}}}}))
        sys.stdout.flush()
'''

FAKE_CODEX = r'''#!{py}
import json, sys
argv = sys.argv[1:]
if argv == ["--version"]:
    print("codex-cli 0.160.1"); sys.exit(0)
assert argv == ["app-server"]
for line in sys.stdin:
    m = json.loads(line)
    if m.get("method") == "initialize":
        print(json.dumps({{"id": m["id"], "result": {{"userAgent": "x"}}}}))
        print(json.dumps({{"method": "account/updated", "params": {{}}}}))
    elif m.get("method") == "model/list":
        if not m["params"].get("cursor"):
            print(json.dumps({{"id": m["id"], "result": {{"data": [
              {{"id": "gpt-7-nova", "model": "gpt-7-nova", "displayName": "GPT-7-Nova", "description": "New", "isDefault": True, "hidden": False,
                "supportedReasoningEfforts": [{{"reasoningEffort": "high"}}]}},
              {{"id": "internal", "model": "internal-x", "displayName": "hidden", "description": "", "isDefault": False, "hidden": True}}],
              "nextCursor": "p2"}}}}))
        else:
            print(json.dumps({{"id": m["id"], "result": {{"data": [
              {{"id": "gpt-6.1-sol", "model": "gpt-6.1-sol", "displayName": "GPT-6.1-Sol", "description": "Workhorse", "isDefault": False, "hidden": False}}],
              "nextCursor": None}}}}))
    sys.stdout.flush()
'''


def _script(path: Path, body: str) -> str:
    path.write_text(body)
    path.chmod(0o755)
    return str(path)


def test_live_model_discovery_exact_ids_no_inference(homes, tmp_path, monkeypatch):
    log = tmp_path / "claude-protocol.log"
    monkeypatch.setenv("AGENTDECK_CLAUDE_BIN", _script(tmp_path / "claude", FAKE_CLAUDE.format(py=sys.executable, log=str(log))))
    monkeypatch.setenv("AGENTDECK_CODEX_BIN", _script(tmp_path / "codex", FAKE_CODEX.format(py=sys.executable)))
    res = models.discover_models(refresh=True)
    a = {x["id"]: x for x in res["agents"]}
    assert a["claude"]["modelSource"] == "live" and a["claude"]["version"] == "2.1.290 (Claude Code)"
    assert [m["id"] for m in a["claude"]["models"]] == ["default", "claude-zeta-9-1-20991231"]
    assert a["claude"]["models"][0]["resolvedModel"] == "claude-zeta-9-1"
    assert [m["id"] for m in a["codex"]["models"]] == ["gpt-7-nova", "gpt-6.1-sol"]  # paginated, hidden dropped
    assert a["codex"]["models"][0]["isDefault"] and a["codex"]["models"][0]["source"] == "codex-app-server-model-list"
    assert "never@leak" not in json.dumps(res)
    sent = [json.loads(x) for x in log.read_text().splitlines()[1:]]
    assert [m["type"] for m in sent] == ["control_request"]  # no user message => no inference
    # cached in memory unless refresh
    assert models.discover_models() is res
    # a later failure falls back to the explicitly labelled cache
    monkeypatch.setenv("AGENTDECK_CODEX_BIN", _script(tmp_path / "codex2", "#!/bin/sh\nexit 3\n"))
    res2 = models.discover_models(refresh=True)
    cx = next(x for x in res2["agents"] if x["id"] == "codex")
    assert cx["modelSource"] == "cache" and cx["error"].startswith("live discovery failed")
    assert cx["models"][0]["source"] == "cache:codex-app-server-model-list"
    cache = Path(os.environ["AGENTDECK_HOME"]) / "provider-cache" / "models.json"
    assert oct(cache.stat().st_mode & 0o777) == "0o600"


def test_missing_cli_is_reported_not_invented(homes, monkeypatch, tmp_path):
    monkeypatch.setenv("AGENTDECK_CLAUDE_BIN", str(tmp_path / "nope"))
    monkeypatch.setenv("AGENTDECK_CODEX_BIN", str(tmp_path / "nope"))
    res = models.discover_models(refresh=True)
    for a in res["agents"]:
        assert a["available"] is False and a["models"] == [] and a["error"]


@pytest.mark.skipif(os.environ.get("AGENTDECK_LIVE") != "1", reason="set AGENTDECK_LIVE=1 for the installed-CLI check")
def test_live_installed_clis(monkeypatch):
    for k in ("AGENTDECK_CLAUDE_HOME", "AGENTDECK_CODEX_HOME", "AGENTDECK_CLAUDE_BIN", "AGENTDECK_CODEX_BIN"):
        monkeypatch.delenv(k, raising=False)
    res = models.discover_models(refresh=True)
    for a in res["agents"]:
        assert a["modelSource"] == "live" and a["models"], a["error"]


def test_headless_claude_cli_is_inspectable_but_not_controllable(homes):
    ch, _ = homes
    write_claude(ch)
    rows = claude.discover_sessions(live={CL_SDK: {"status": "busy", "entrypoint": "sdk-cli", "cwd": "/Users/test/app"}})
    s = next(r for r in rows if r["nativeId"] == CL_SDK)
    assert s["status"] == "working" and s["live"]
    assert not s["canResume"] and not any(s["capabilities"].values())


def test_live_claude_bypasses_history_count_and_age_limits(homes):
    ch, _ = homes
    p = write_claude(ch)
    old = time.time() - 40 * 86400
    os.utime(p, (old, old))
    for i in range(claude.MAX_SESSIONS + 5):
        row = {"type": "user", "entrypoint": "cli", "cwd": "/tmp/project", "message": {"content": "historical"}}
        (p.parent / f"history-{i}.jsonl").write_text(json.dumps(row) + "\n")
    rows = claude.discover_sessions(live={CL_ID: {"status": "busy", "entrypoint": "cli"}})
    assert next(s for s in rows if s["nativeId"] == CL_ID)["status"] == "working"
    assert len([s for s in rows if not s["live"]]) == claude.MAX_SESSIONS


def test_live_claude_without_transcript_is_not_hidden(homes):
    ch, _ = homes
    (ch / "projects").mkdir(parents=True)
    [s] = claude.discover_sessions(live={CL_ID: {"status": "busy", "entrypoint": "sdk-cli", "cwd": "/tmp/project"}})
    assert s["nativeId"] == CL_ID and s["status"] == "working" and s["live"]
