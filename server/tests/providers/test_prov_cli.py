from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from agentdeck import cli

SERVER = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("agent,args,expected", [
    ("claude", [], True),
    ("claude", ["--model", "opus"], True),
    ("claude", ["fix the bug"], True),
    ("claude", ["--resume", "aaaaaaaa-1111-4222-8333-444444444444"], True),
    ("claude", ["-p", "hello"], False),
    ("claude", ["--print", "x"], False),
    ("claude", ["--version"], False),
    ("claude", ["mcp", "list"], False),
    ("claude", ["doctor"], False),
    ("codex", [], True),
    ("codex", ["-m", "gpt-6.1-sol"], True),
    ("codex", ["resume", "--last"], True),
    ("codex", ["fork"], True),
    ("codex", ["exec", "do it"], False),
    ("codex", ["app-server"], False),
    ("codex", ["login"], False),
    ("codex", ["--version"], False),
])
def test_interactive_detection(agent, args, expected):
    assert cli.is_interactive_invocation(agent, args) is expected


def test_resume_target():
    u = "aaaaaaaa-1111-4222-8333-444444444444"
    assert cli.resume_target("claude", ["--resume", u]) == u
    assert cli.resume_target("claude", [f"--resume={u}"]) == u
    assert cli.resume_target("claude", ["--resume"]) is None
    assert cli.resume_target("codex", ["resume", u]) == u
    assert cli.resume_target("codex", ["resume", "--last"]) is None


def _fake_native(tmp_path: Path, name: str) -> Path:
    p = tmp_path / name
    p.write_text("#!/bin/sh\necho \"NATIVE $0 $*\"\n")
    p.chmod(0o755)
    return p


def _run_cli(args, env, stdin=subprocess.DEVNULL):
    full = {**os.environ, **env, "PYTHONPATH": str(SERVER)}
    return subprocess.run([sys.executable, "-m", "agentdeck.cli", *args], capture_output=True, text=True,
                          env=full, stdin=stdin, timeout=30)


def test_run_without_tty_execs_native_binary_unchanged(tmp_path):
    fake = _fake_native(tmp_path, "claude")
    r = _run_cli(["run", "claude", "-p", "hi there"], {"AGENTDECK_CLAUDE_BIN": str(fake), "AGENTDECK_HOME": str(tmp_path)})
    assert r.returncode == 0 and r.stdout.strip() == f"NATIVE {fake} -p hi there"
    r = _run_cli(["run", "codex", "--", "exec", "x"], {"AGENTDECK_CODEX_BIN": str(_fake_native(tmp_path, "codex")),
                                                      "AGENTDECK_HOME": str(tmp_path)})
    assert r.stdout.strip().endswith("codex exec x")


def test_install_shell_block_idempotent_and_reversible(tmp_path):
    rc = tmp_path / "zshrc"
    rc.write_text("export FOO=1\n")
    ns = argparse.Namespace(target=str(rc), dry_run=True, uninstall=False, python="/venv/bin/python",
                            claude_bin="/Users/x/.local/bin/claude", codex_bin="/Users/x/.local/bin/codex")
    assert cli.cmd_install_shell(ns) == 0
    assert rc.read_text() == "export FOO=1\n"  # dry run writes nothing
    ns.dry_run = False
    cli.cmd_install_shell(ns)
    once = rc.read_text()
    cli.cmd_install_shell(ns)
    assert rc.read_text() == once  # idempotent
    assert once.startswith("export FOO=1\n") and once.count(cli.MARK_BEGIN) == 1
    assert '      /Users/x/.local/bin/claude "$@"; return' in once  # fallback calls the absolute native binary
    assert "AGENTDECK_DISABLE" in once and "AGENTDECK_SESSION_ID" in once
    ns.claude_bin = "/opt/claude"
    cli.cmd_install_shell(ns)
    assert rc.read_text().count(cli.MARK_BEGIN) == 1 and "/opt/claude" in rc.read_text()  # replaced, not appended
    ns.uninstall = True
    cli.cmd_install_shell(ns)
    assert rc.read_text().strip() == "export FOO=1"


@pytest.mark.skipif(not shutil.which("zsh"), reason="zsh missing")
def test_shell_block_bypass_and_no_recursion(tmp_path):
    fake_claude = _fake_native(tmp_path, "claude")
    fake_codex = _fake_native(tmp_path, "codex")
    block = cli.shell_block(sys.executable, str(SERVER), str(fake_claude), str(fake_codex))
    f = tmp_path / "block.zsh"
    f.write_text(block)
    assert subprocess.run(["zsh", "-n", str(f)]).returncode == 0
    env = {"PATH": f"{tmp_path}:/usr/bin:/bin", "HOME": str(tmp_path), "AGENTDECK_HOME": str(tmp_path / "ad")}
    # interactive zsh without a TTY: function falls back to the absolute native binary (no recursion)
    r = subprocess.run(["zsh", "-f", "-i", "-c", f"source {f}; claude --hello; codex exec y; "
                        "AGENTDECK_DISABLE=1 claude d; command claude c; type claude | head -1"],
                       capture_output=True, text=True, env=env, stdin=subprocess.DEVNULL, timeout=30)
    out = r.stdout.splitlines()
    assert out[0] == f"NATIVE {fake_claude} --hello"
    assert out[1] == f"NATIVE {fake_codex} exec y"
    assert out[2] == f"NATIVE {fake_claude} d"
    assert out[3] == f"NATIVE {fake_claude} c"  # `command claude` resolves via PATH to the native binary
    assert "function" in out[4]
    # a non-interactive shell never defines the wrappers
    r = subprocess.run(["zsh", "-f", "-c", f"source {f}; type claude"], capture_output=True, text=True, env=env, timeout=30)
    assert "function" not in r.stdout


def test_hook_mapping(monkeypatch):
    monkeypatch.setenv("AGENTDECK_SESSION_ID", "abc123abc123")
    assert cli.hook_to_notify("claude", {"hook_event_name": "Stop", "session_id": "n"})["kind"] == "completed"
    b = cli.hook_to_notify("claude", {"hook_event_name": "Notification", "message": "Claude needs permission to use Bash"})
    assert b == {"sessionId": "abc123abc123", "kind": "input", "title": "Claude needs input",
                 "body": "Claude needs permission to use Bash"}
    assert cli.hook_to_notify("claude", {"hook_event_name": "PreToolUse"}) is None
    assert cli.hook_to_notify("codex", {"type": "agent-turn-complete", "last-assistant-message": "done"})["body"] == "done"
    monkeypatch.delenv("AGENTDECK_SESSION_ID")
    assert cli.hook_to_notify("claude", {"hook_event_name": "Stop", "session_id": "native-1"})["sessionId"] == "native-1"
    assert cli.hook_to_notify("claude", {"hook_event_name": "Stop"}) is None


def test_hook_never_fails_even_without_server(tmp_path):
    r = _run_cli(["hook", "claude"], {"AGENTDECK_HOME": str(tmp_path), "AGENTDECK_SESSION_ID": "abc123abc123"},
                 stdin=subprocess.PIPE)
    assert r.returncode == 0


def test_notify_body_progress_rules(monkeypatch):
    monkeypatch.setenv("AGENTDECK_SESSION_ID", "abc123abc123")
    ns = argparse.Namespace(session=None, title="Tests", body="running", kind="progress", current=3, total=10,
                            unit="files", stage="pytest")
    assert cli.build_notify_body(ns) == {"sessionId": "abc123abc123", "title": "Tests", "body": "running",
                                         "kind": "progress", "progress": {"current": 3, "total": 10, "unit": "files"},
                                         "stage": "pytest"}
    ns.total, ns.current = None, None
    assert "progress" not in cli.build_notify_body(ns)  # unknown total => no invented percentage
    ns.total = 5
    with pytest.raises(SystemExit):
        cli.build_notify_body(ns)


def test_pair_forwards_leading_options_to_admin(monkeypatch):
    from agentdeck import admin, cli

    seen = {}
    monkeypatch.setattr(admin, "main", lambda args: seen.setdefault("args", args) and 0)
    assert cli.main(["pair", "--json", "--server", "https://mac.tail0.ts.net:10443"]) == 0
    assert seen["args"] == ["pair", "--json", "--server", "https://mac.tail0.ts.net:10443"]
