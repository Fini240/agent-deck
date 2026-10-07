"""TerminalManager against a real tmux server and a fake TUI in a real PTY."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import pytest

from agentdeck.errors import ConflictError, InvalidInputError, NotFoundError, StaleApprovalError
from agentdeck.terminal import TerminalManager

TMUX = shutil.which("tmux") or "/opt/homebrew/bin/tmux"
pytestmark = pytest.mark.skipif(not os.access(TMUX, os.X_OK), reason="tmux not installed")
FAKE = Path(__file__).parent / "fake_tui.py"


def wait_for(pred, timeout=8.0, interval=0.1):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        v = pred()
        if v:
            return v
        time.sleep(interval)
    raise AssertionError("condition not met in time")


def events(log: Path) -> list[dict]:
    if not log.exists():
        return []
    return [json.loads(x) for x in log.read_text().splitlines() if x.strip()]


def names(log: Path) -> list[str]:
    return [e["event"] for e in events(log)]


@pytest.fixture
def env(monkeypatch):
    root = Path(tempfile.mkdtemp(prefix="adt-", dir="/tmp"))
    state = root / "st"
    wrapper = root / "fakebin"
    wrapper.write_text(f"#!/bin/sh\nexec {sys.executable} {FAKE} \"$@\"\n")
    wrapper.chmod(0o755)
    monkeypatch.setenv("AGENTDECK_CLAUDE_BIN", str(wrapper))
    monkeypatch.setenv("AGENTDECK_CODEX_BIN", str(wrapper))
    claude_home = root / "claude"
    (claude_home / "sessions").mkdir(parents=True)
    (claude_home / "projects").mkdir()
    monkeypatch.setenv("AGENTDECK_CLAUDE_HOME", str(claude_home))
    monkeypatch.setenv("AGENTDECK_CODEX_HOME", str(root / "codex"))
    monkeypatch.setenv("AGENTDECK_CODEX_CONTROL_SOCK", str(root / "no-daemon.sock"))
    tm = TerminalManager(state)
    ctx = {"root": root, "tm": tm, "others": []}
    yield ctx
    subprocess.run([TMUX, "-S", str(state / "tmux.sock"), "kill-server"], capture_output=True)
    for sock in ctx["others"]:
        subprocess.run([TMUX, "-L", sock, "kill-server"], capture_output=True)
    shutil.rmtree(root, ignore_errors=True)


def start(ctx, style="claude", name="a", **kw):
    log = ctx["root"] / f"{name}.log"
    pane_env = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"], "LANG": "en_US.UTF-8",
                "FAKE_TUI_STYLE": style, "FAKE_TUI_LOG": str(log)}
    s = ctx["tm"].start_session(style, str(ctx["root"]), env=pane_env, **kw)
    wait_for(lambda: "start" in names(log))
    wait_for(lambda: ctx["tm"].get_session(s["id"])["status"] in ("idle", "working", "needs_input"))
    return s, log


def test_start_lists_managed_idle_session_with_native_id(env):
    s, log = start(env)
    assert s["managed"] and s["agent"] == "claude"
    uuid.UUID(s["nativeId"])  # Claude gets an explicit --session-id
    start_ev = events(log)[0]
    assert start_ev["argv"][:2] == ["--session-id", s["nativeId"]]
    assert start_ev["session"] == s["id"]  # AGENTDECK_SESSION_ID exported into the pane
    listed = {x["id"]: x for x in env["tm"].list_sessions()}
    assert listed[s["id"]]["status"] == "idle"
    assert listed[s["id"]]["capabilities"] == {"send": True, "interrupt": True, "approve": True, "stop": True}
    assert "Fake claude TUI" in env["tm"].preview(s["id"])
    reg = env["tm"].state_dir / "sessions.json"
    assert oct(reg.stat().st_mode & 0o777) == "0o600"


def test_send_while_idle_never_interrupts_and_uses_bracketed_paste(env):
    s, log = start(env)
    text = "first line\nsecond line with 'quotes' && $(echo no-shell)\n\tindented"
    env["tm"].send(s["id"], text, interrupt=True)
    wait_for(lambda: "submit" in names(log))
    ev = events(log)
    assert "interrupt" not in names(log) and "escape_idle" not in names(log)
    assert "ctrl_c" not in names(log)
    pastes = [e for e in ev if e["event"] == "paste"]
    assert len(pastes) == 1 and pastes[0]["text"] == text  # newlines stayed inside the bracketed paste
    assert [e["text"] for e in ev if e["event"] == "submit"] == [text]
    assert "typed" not in names(log)  # nothing went through as raw keystrokes


def test_send_while_working_interrupts_once_and_clears_restored_prompt(env):
    s, log = start(env)
    env["tm"].send(s["id"], "work forever on line one\nand line two", interrupt=True)
    wait_for(lambda: env["tm"].get_session(s["id"])["status"] == "working")
    assert env["tm"].get_session(s["id"])["stage"].startswith("Ideating…")
    env["tm"].send(s["id"], "follow-up instead", interrupt=True)
    wait_for(lambda: names(log).count("submit") == 2)
    n = names(log)
    assert n.count("interrupt") == 1
    assert "ctrl_c" not in n
    assert "ctrl_u" in n  # the restored multi-line prompt was cleared, not appended to
    submits = [e["text"] for e in events(log) if e["event"] == "submit"]
    assert submits == ["work forever on line one\nand line two", "follow-up instead"]
    # restored text belonged to the interrupted turn, so it is not stored as a user draft
    assert not (env["tm"].state_dir / "cleared-drafts.jsonl").exists()


def test_send_without_interrupt_queues_natively(env):
    s, log = start(env)
    env["tm"].send(s["id"], "work forever", interrupt=True)
    wait_for(lambda: env["tm"].get_session(s["id"])["status"] == "working")
    env["tm"].send(s["id"], "queued note", interrupt=False)
    wait_for(lambda: names(log).count("submit") == 2)
    assert "interrupt" not in names(log)


def test_desk_draft_is_cleared_and_saved_privately(env):
    s, log = start(env)
    pane = env["tm"].registry.snapshot()["sessions"][s["id"]]["paneId"]
    env["tm"]._tmux("send-keys", "-t", pane, "-l", "half typed at the desk")
    wait_for(lambda: env["tm"].get_session(s["id"]) and "typed" in names(log))
    wait_for(lambda: names(log).count("typed") == len("half typed at the desk"))
    env["tm"].send(s["id"], "phone message")
    wait_for(lambda: "submit" in names(log))
    assert [e["text"] for e in events(log) if e["event"] == "submit"] == ["phone message"]
    saved = env["tm"].state_dir / "cleared-drafts.jsonl"
    assert json.loads(saved.read_text().splitlines()[-1])["text"] == "half typed at the desk"
    assert oct(saved.stat().st_mode & 0o777) == "0o600"


def test_stop_only_interrupts_working_and_keeps_chat(env):
    s, log = start(env)
    env["tm"].stop(s["id"])
    assert "escape_idle" not in names(log) and "interrupt" not in names(log)
    env["tm"].send(s["id"], "work forever")
    wait_for(lambda: env["tm"].get_session(s["id"])["status"] == "working")
    env["tm"].stop(s["id"])
    assert names(log).count("interrupt") == 1
    after = env["tm"].get_session(s["id"])
    assert after["status"] == "idle" and after["capabilities"]["send"]  # same live pane, same chat
    assert "ctrl_c" not in names(log)


def test_approval_listing_choice_and_stale_rejection(env):
    s, log = start(env)
    env["tm"].send(s["id"], "ask please")
    wait_for(lambda: env["tm"].approvals(s["id"]))
    [ap] = env["tm"].approvals(s["id"])
    assert ap["title"] == "Do you want to proceed?"
    assert [c["id"] for c in ap["choices"]] == ["1", "2", "3"]
    assert ap["choices"][2]["label"] == "No"
    assert "rm -rf /tmp/fake-" in ap["detail"]
    with pytest.raises(StaleApprovalError):
        env["tm"].respond_approval(s["id"], ap["id"], "9")
    env["tm"].respond_approval(s["id"], ap["id"], "3")
    choice = [e for e in events(log) if e["event"] == "choice"]
    assert choice and choice[0]["label"] == "No"
    with pytest.raises(StaleApprovalError):
        env["tm"].respond_approval(s["id"], ap["id"], "1")  # prompt is gone
    # a new, different prompt gets a new id; the old id stays stale
    env["tm"].send(s["id"], "ask again")
    wait_for(lambda: env["tm"].approvals(s["id"]))
    [ap2] = env["tm"].approvals(s["id"])
    assert ap2["id"] != ap["id"]
    with pytest.raises(StaleApprovalError):
        env["tm"].respond_approval(s["id"], ap["id"], "1")
    assert len([e for e in events(log) if e["event"] == "choice"]) == 1


def test_choice_that_exits_agent_counts_as_answered(env):
    s, log = start(env)
    env["tm"].send(s["id"], "ask exit")
    wait_for(lambda: env["tm"].approvals(s["id"]))
    [ap] = env["tm"].approvals(s["id"])
    no_exit = next(c for c in ap["choices"] if c["label"] == "No, exit")
    env["tm"].respond_approval(s["id"], ap["id"], no_exit["id"])  # must not raise
    assert "exit" in names(log)


def test_send_during_dialog_requires_interrupt(env):
    s, log = start(env)
    env["tm"].send(s["id"], "ask")
    wait_for(lambda: env["tm"].approvals(s["id"]))
    from agentdeck.errors import NotReadyError

    with pytest.raises(NotReadyError):
        env["tm"].send(s["id"], "x", interrupt=False)
    env["tm"].send(s["id"], "never mind", interrupt=True)
    wait_for(lambda: names(log).count("submit") == 2)
    assert names(log).count("dialog_cancel") == 1


def test_never_touches_unrelated_panes(env):
    s, log = start(env)
    # an unrelated tmux server (like the user's own) running the same fake
    other_sock = "adt-other-" + uuid.uuid4().hex[:6]
    env["others"].append(other_sock)
    other_log = env["root"] / "other.log"
    subprocess.run([TMUX, "-L", other_sock, "-f", "/dev/null", "new-session", "-d", "-s", "user", "-e",
                    f"FAKE_TUI_LOG={other_log}", sys.executable, str(FAKE)], check=True)
    # an untagged pane inside our own server
    stray_log = env["root"] / "stray.log"
    env["tm"]._tmux("new-session", "-d", "-s", "stray", "-e", f"FAKE_TUI_LOG={stray_log}", "--",
                    sys.executable, str(FAKE))
    wait_for(lambda: "start" in names(other_log) and "start" in names(stray_log))
    tm = env["tm"]
    tm.send(s["id"], "hello")
    tm.input_key(s["id"], "down")
    tm.stop(s["id"])
    tm.list_sessions()
    assert [x["id"] for x in tm.list_sessions()] == [s["id"]]  # untagged pane is not adopted
    time.sleep(0.5)
    assert names(other_log) == ["start"] and names(stray_log) == ["start"]
    # tampering the registry to point at the untagged pane is refused (tag check)
    stray_pane = tm._tmux("display-message", "-p", "-t", "stray", "#{pane_id}").stdout.decode().strip()
    with tm.registry.locked() as data:
        data["sessions"][s["id"]]["paneId"] = stray_pane
    with pytest.raises(ConflictError):
        tm.send(s["id"], "should not arrive")
    time.sleep(0.3)
    assert names(stray_log) == ["start"]
    with pytest.raises(NotFoundError):
        tm.send("../../etc", "x")


def test_input_key_whitelist(env):
    s, log = start(env)
    env["tm"].input_key(s["id"], "tab")
    wait_for(lambda: "tab" in names(log))
    with pytest.raises(InvalidInputError):
        env["tm"].input_key(s["id"], "C-c")
    with pytest.raises(InvalidInputError):
        env["tm"].send(s["id"], "bad \x1b[201~ escape")


def test_codex_style_placeholder_and_send(env):
    s, log = start(env, style="codex", name="c")
    assert env["tm"].get_session(s["id"])["status"] == "idle"
    env["tm"].send(s["id"], "work forever")
    wait_for(lambda: env["tm"].get_session(s["id"])["status"] == "working")
    env["tm"].send(s["id"], "steer this way")
    wait_for(lambda: names(log).count("submit") == 2)
    assert names(log).count("interrupt") == 1
    assert [e["text"] for e in events(log) if e["event"] == "submit"][-1] == "steer this way"


def test_exited_agent_goes_offline_and_rejects_control(env):
    s, log = start(env)
    pane = env["tm"].registry.snapshot()["sessions"][s["id"]]["paneId"]
    env["tm"]._tmux("send-keys", "-t", pane, "C-c")
    env["tm"]._tmux("send-keys", "-t", pane, "C-c")  # fake exits on double Ctrl-C while idle
    wait_for(lambda: env["tm"].get_session(s["id"])["status"] == "offline")
    sess = env["tm"].get_session(s["id"])
    assert sess["capabilities"]["send"] is False
    with pytest.raises(ConflictError):
        env["tm"].send(s["id"], "hello?")


def test_resume_refuses_live_elsewhere_and_dedupes_managed(env):
    native = str(uuid.uuid4())
    sessions_dir = Path(os.environ["AGENTDECK_CLAUDE_HOME"]) / "sessions"
    (sessions_dir / f"{os.getpid()}.json").write_text(json.dumps({"pid": os.getpid(), "sessionId": native, "status": "idle"}))
    with pytest.raises(ConflictError):
        env["tm"].resume_session({"agent": "claude", "nativeId": native, "cwd": str(env["root"])})
    (sessions_dir / f"{os.getpid()}.json").unlink()
    # transcript exists -> resume starts exactly one managed pane; a second call returns the same one
    proj = Path(os.environ["AGENTDECK_CLAUDE_HOME"]) / "projects" / "-tmp-x"
    proj.mkdir(parents=True)
    (proj / f"{native}.jsonl").write_text(json.dumps({"type": "user", "cwd": str(env["root"]), "sessionId": native,
                                                      "message": {"role": "user", "content": "hi"},
                                                      "timestamp": "2026-10-07T00:00:00Z"}) + "\n")
    log = env["root"] / "r.log"
    from agentdeck import terminal as term

    orig = term.login_environment
    term.login_environment = lambda: {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"],
                                      "FAKE_TUI_LOG": str(log)}
    try:
        first = env["tm"].resume_session({"agent": "claude", "nativeId": native, "cwd": str(env["root"])})
        wait_for(lambda: "start" in names(log))
        second = env["tm"].resume_session({"agent": "claude", "nativeId": native, "cwd": str(env["root"])})
    finally:
        term.login_environment = orig
    assert first["id"] == second["id"] and first["nativeId"] == native
    assert events(log)[0]["argv"][:2] == ["--resume", native]
    assert len([x for x in env["tm"].list_sessions() if x["capabilities"]["send"]]) == 1
    with pytest.raises(InvalidInputError):
        env["tm"].resume_session({"agent": "claude", "nativeId": "not-a-uuid; rm -rf /", "cwd": "/"})


def test_close_does_not_kill_sessions(env):
    s, _ = start(env)
    env["tm"].close()
    assert TerminalManager(env["tm"].state_dir).get_session(s["id"])["capabilities"]["send"]


def test_claude_registry_busy_counts_as_working_when_screen_shows_no_spinner(env):
    s, log = start(env)
    pane_pid = env["tm"]._live_panes()[s["id"]]["pid"]
    reg = Path(os.environ["AGENTDECK_CLAUDE_HOME"]) / "sessions" / f"{pane_pid}.json"
    reg.write_text(json.dumps({"pid": pane_pid, "sessionId": s["nativeId"], "status": "busy"}))
    assert env["tm"].get_session(s["id"])["status"] == "working"
    reg.write_text(json.dumps({"pid": pane_pid, "sessionId": s["nativeId"], "status": "idle"}))
    assert env["tm"].get_session(s["id"])["status"] == "idle"
    reg.write_text(json.dumps({"pid": pane_pid, "sessionId": s["nativeId"], "status": "busy"}))
    from agentdeck.errors import NotReadyError

    # Escape is sent exactly once; the (fake) TUI never reports idle via the registry, so send gives up safely
    with pytest.raises(NotReadyError):
        env["tm"].send(s["id"], "follow-up", interrupt=True)
    assert names(log).count("escape_idle") == 1 and "submit" not in names(log)


def test_cli_run_creates_managed_pane_and_attaches_terminal(env):
    """`agentdeck run claude ...` from a real TTY: managed pane on the private server, user terminal attached."""
    outer = "adt-outer-" + uuid.uuid4().hex[:6]
    env["others"].append(outer)
    log = env["root"] / "run.log"
    home = env["root"] / "home"
    cmd = (f"env AGENTDECK_HOME={home} FAKE_TUI_LOG={log} PYTHONPATH={Path(__file__).resolve().parents[2]} "
           f"AGENTDECK_CLAUDE_BIN={os.environ['AGENTDECK_CLAUDE_BIN']} "
           f"{sys.executable} -m agentdeck.cli run claude --model sonnet")
    subprocess.run([TMUX, "-L", outer, "-f", "/dev/null", "new-session", "-d", "-x", "120", "-y", "40", "-s", "term",
                    "sh", "-c", cmd + "; echo RUN-EXITED; sleep 30"], check=True)
    wait_for(lambda: "start" in names(log), timeout=15)
    start_ev = events(log)[0]
    assert start_ev["argv"][0] == "--session-id" and start_ev["argv"][2:] == ["--model", "sonnet"]
    tm = TerminalManager(home / "terminal")
    [s] = tm.list_sessions()
    assert s["id"] == start_ev["session"] and s["capabilities"]["send"]
    wait_for(lambda: tm.get_session(s["id"])["attached"], timeout=10)  # the user's terminal is attached
    # the phone sends into the same pane the terminal shows
    tm.send(s["id"], "hello from phone")
    shown = lambda: subprocess.run([TMUX, "-L", outer, "capture-pane", "-p", "-t", "term"],  # noqa: E731
                                   capture_output=True, text=True).stdout
    wait_for(lambda: "echo: hello from phone" in shown(), timeout=10)
    # resuming the same native chat from another terminal attaches instead of starting a duplicate
    from agentdeck import cli

    assert cli.resume_target("claude", ["--resume", s["nativeId"]]) == s["nativeId"]
    assert tm.find_live("claude", s["nativeId"])["id"] == s["id"]
    subprocess.run([TMUX, "-S", str(tm.socket), "kill-server"], capture_output=True)
    wait_for(lambda: "RUN-EXITED" in shown(), timeout=10)  # attach returns to the shell when the agent ends


def test_post_enter_timeout_is_uncertain_not_safe_to_resend(env, monkeypatch):
    from agentdeck.errors import DeliveryUncertainError, NotReadyError
    s, log = start(env)
    def timeout(*args):
        raise NotReadyError("no confirmation")
    monkeypatch.setattr(env["tm"], "_wait_submitted", timeout)
    with pytest.raises(DeliveryUncertainError):
        env["tm"].send(s["id"], "one reply")
    wait_for(lambda: "submit" in names(log))
    assert [e["text"] for e in events(log) if e["event"] == "submit"] == ["one reply"]


def test_concurrent_resume_across_managers_spawns_one_pane(env):
    from concurrent.futures import ThreadPoolExecutor
    tm = env["tm"]
    other = TerminalManager(tm.state_dir)
    native = str(uuid.uuid4())
    pane_env = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"], "LANG": "en_US.UTF-8",
                "FAKE_TUI_STYLE": "claude", "FAKE_TUI_LOG": str(env["root"] / "resume.log")}
    def run(manager):
        return manager.start_session("claude", str(env["root"]), resume_native_id=native, env=pane_env)
    with ThreadPoolExecutor(max_workers=2) as pool:
        a, b = pool.submit(run, tm), pool.submit(run, other)
        first, second = a.result(8), b.result(8)
    assert first["id"] == second["id"]
    assert len(tm.registry.snapshot()["sessions"]) == 1


def test_codex_does_not_claim_unrelated_cwd_candidate(env, monkeypatch):
    from agentdeck.providers import codex
    s, _ = start(env, style="codex")
    rec = env["tm"].registry.snapshot()["sessions"][s["id"]]
    monkeypatch.setattr(codex, "thread_for_pids", lambda pids: None)
    monkeypatch.setattr(codex, "find_new_thread", lambda *args: "aaaaaaaa-1111-4222-8333-444444444444")
    env["tm"]._associate(rec, {"pid": os.getpid()})
    assert rec["nativeId"] is None
