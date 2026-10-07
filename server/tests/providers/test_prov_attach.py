"""`agentdeck attach` session picker: pure helpers plus PTY and non-TTY end-to-end runs.

The end-to-end runs use a small driver that swaps in a fake TerminalManager and a fake exec,
so no tmux server, managed session or user state is touched.
"""

from __future__ import annotations

import fcntl
import io
import json
import os
import select
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

import pytest

from agentdeck import cli

SERVER = Path(__file__).resolve().parents[2]

DRIVER = r'''
import json, os, sys, termios
from agentdeck import cli, terminal
from agentdeck.errors import ConflictError

sessions = json.loads(os.environ["FAKE_SESSIONS"])
vanish = set(json.loads(os.environ.get("FAKE_VANISH", "[]")))
before = termios.tcgetattr(0) if os.isatty(0) else None

def restored():
    return "RESTORED" if before is None or termios.tcgetattr(0) == before else "NOT-RESTORED"

class FakeTM:
    def list_sessions(self):
        return sessions
    def attach_command(self, sid):
        if sid in vanish or sid not in {s["id"] for s in sessions}:
            raise ConflictError("the managed terminal for this session has ended")
        return ["/usr/bin/true", "attach-session", "-t", sid]

terminal.TerminalManager = FakeTM

def fake_exec(cmd):
    print("EXEC", cmd[-1], restored(), flush=True)
    os._exit(0)

cli._exec_attach = fake_exec
rc = cli.main(["attach", *sys.argv[1:]])
print("RC", rc, restored(), flush=True)
sys.exit(rc)
'''


def _sess(i: int, **kw) -> dict:
    base = {"id": f"sess{i:04d}", "agent": "claude" if i % 2 else "codex", "title": f"Task {i}",
            "status": "idle", "cwd": f"/tmp/project-{i}", "attached": False,
            "capabilities": {"send": True, "interrupt": True, "approve": True, "stop": True}}
    base.update(kw)
    return base


@pytest.fixture
def driver(tmp_path: Path) -> Path:
    d = tmp_path / "driver"
    d.mkdir()
    p = d / "attach_driver.py"
    p.write_text(DRIVER)
    return p


def _env(sessions, vanish=(), **extra) -> dict:
    env = {**os.environ, "PYTHONPATH": str(SERVER), "FAKE_SESSIONS": json.dumps(sessions),
           "FAKE_VANISH": json.dumps(list(vanish)), "TERM": "xterm-256color"}
    env.pop("TMUX", None)
    env.update(extra)
    return env


def _read_until(master: int, buf: bytearray, needle: bytes, deadline: float) -> bool:
    while needle not in buf:
        left = deadline - time.monotonic()
        if left <= 0:
            return False
        if select.select([master], [], [], left)[0]:
            try:
                chunk = os.read(master, 4096)
            except OSError:  # EIO once the child closed the pty (macOS/Linux)
                return needle in buf
            if not chunk:
                return needle in buf
            buf.extend(chunk)
    return True


def run_pty(driver: Path, sessions, keys=(), args=(), vanish=(), rows=24, cols=100, wait_for=b"choose a session",
            **extra) -> tuple[int, str]:
    master, slave = os.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    proc = subprocess.Popen([sys.executable, str(driver), *args], stdin=slave, stdout=slave, stderr=slave,
                            env=_env(sessions, vanish, **extra), start_new_session=True)
    os.close(slave)
    buf = bytearray()
    try:
        if wait_for is not None:
            assert _read_until(master, buf, wait_for, time.monotonic() + 15), buf.decode(errors="replace")
        for k in keys:
            os.write(master, k)
            time.sleep(0.15)  # lets a lone Escape be seen on its own and the menu redraw
        _read_until(master, buf, b"\0never\0", time.monotonic() + 3 if proc.poll() is None else 0.5)
        rc = proc.wait(timeout=15)
        _read_until(master, buf, b"\0never\0", time.monotonic() + 0.3)
    finally:
        if proc.poll() is None:
            proc.kill()
        os.close(master)
    return rc, buf.decode(errors="replace")


def run_pipe(driver: Path, sessions, args=()) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(driver), *args], capture_output=True, text=True,
                          stdin=subprocess.DEVNULL, env=_env(sessions), timeout=20)


# ---------------------------------------------------------------- pure helpers

def test_clean_text_strips_escape_and_control_sequences():
    nasty = "a\x1b]0;pwned\x07b\x1b[31mc\x1b[0m\x1bPdcs\x1b\\d\x07\x08\r\ne\tf‮g\x9b2Jh\x1b"
    out = cli.clean_text(nasty)
    assert "\x1b" not in out and "\x07" not in out and "‮" not in out and "\x9b" not in out
    assert all(ch.isprintable() for ch in out)
    assert out.startswith("abcd") and "pwned" not in out
    assert cli.clean_text(None) == "" and len(cli.clean_text("x" * 1000, 50)) == 50


def test_fit_respects_cell_width():
    assert cli.fit("hello", 10) == "hello"
    assert cli.fit("hello world", 6) == "hello…"
    assert cli.fit("/a/very/long/path", 8, left=True) == "…ng/path"
    wide = cli.fit("界界界界界", 5)
    assert sum(2 if c == "界" else 1 for c in wide) <= 5
    assert cli.fit("x", 0) == ""


def test_session_row_shows_provider_status_title_dir_and_attached(monkeypatch):
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: Path("/home/u")))
    s = _sess(1, title="Fix \x1b[2Jlogin", cwd="/home/u/code/app", status="working", attached=True)
    row = cli.session_row(s, 80, 3)
    assert row.startswith(" 3. claude  working")
    assert "Fix login" in row and "~/code/app" in row and row.endswith("[attached]")
    assert "\x1b" not in row
    long = cli.session_row(_sess(2, title="T" * 200, cwd="/x/" + "d" * 200), 60, 1)
    assert len(long) <= 60 and "…" in long


def test_parse_keys():
    assert cli.parse_keys(b"\x1b[A\x1bOB\r") == ["up", "down", "enter"]
    assert cli.parse_keys(b"\x1b") == ["cancel"]
    assert cli.parse_keys(b"q") == ["cancel"] and cli.parse_keys(b"\x03") == ["cancel"]
    assert cli.parse_keys(b"jk3\x1b[5~\x1b[6~\x1b[H\x1b[F") == ["down", "up", "digit3", "pgup", "pgdn", "home", "end"]
    assert cli.parse_keys(b"x0") == ["other", "other"]


def test_menu_navigation_wraps_and_prefers_unattached():
    m = cli.Menu([_sess(1, attached=True), _sess(2), _sess(3)])
    assert m.index == 1
    m.key("down"), m.key("down")
    assert m.index == 0
    m.key("up")
    assert m.index == 2
    m.key("digit2")
    assert m.index == 1
    assert m.key("digit9") is None and m.index == 1
    assert m.key("enter") == "enter" and m.key("cancel") == "cancel"


def test_menu_scrolls_within_terminal_height():
    m = cli.Menu([_sess(i) for i in range(1, 21)])
    for rows in (5, 8, 24):
        for _ in range(25):
            m.key("down")
            lines = m.lines(60, rows)
            assert len(lines) <= rows - 1
            assert any(line.startswith("\x1b[7m> ") for line in lines)  # selection always visible
            assert all(len(cli._ESCAPE_RE.sub("", line)) <= 59 for line in lines)
    m.key("end")
    assert "(" in m.lines(60, 8)[-1] and "of 20" in m.lines(60, 8)[-1]


def test_numbered_pick():
    sessions = [_sess(1), _sess(2)]
    out = io.StringIO()
    assert cli.numbered_pick(sessions, io.StringIO("2\n"), out) == "sess0002"
    assert " 1. claude" in out.getvalue() and " 2. codex" in out.getvalue()
    out = io.StringIO()
    assert cli.numbered_pick(sessions, io.StringIO("7\nabc\n1\n"), out) == "sess0001"
    assert out.getvalue().count("enter a number from 1 to 2") == 2
    assert cli.numbered_pick(sessions, io.StringIO(""), io.StringIO()) is None  # EOF
    assert cli.numbered_pick(sessions, io.StringIO("\n"), io.StringIO()) is None
    assert cli.numbered_pick(sessions, io.StringIO("q\n"), io.StringIO()) is None


# ---------------------------------------------------------------- PTY end-to-end

def test_pty_arrow_selection_attaches_and_restores_terminal(driver):
    rc, out = run_pty(driver, [_sess(1), _sess(2), _sess(3)], keys=[b"\x1b[B", b"\x1b[B", b"\x1b[A", b"\r"])
    assert rc == 0, out
    assert "EXEC sess0002 RESTORED" in out
    assert "\x1b[?25h" in out  # cursor shown again before exec
    assert "Traceback" not in out


def test_pty_single_session_still_shows_menu(driver):
    rc, out = run_pty(driver, [_sess(1, title="Only one")], keys=[b"\r"])
    assert rc == 0 and "Only one" in out and "EXEC sess0001 RESTORED" in out


def test_pty_fragmented_arrow_selects_instead_of_cancelling(driver):
    rc, out = run_pty(driver, [_sess(1), _sess(2)], keys=[b"\x1b[", b"B", b"\r"])
    assert rc == 0 and "EXEC sess0002 RESTORED" in out
    assert "attach cancelled" not in out


@pytest.mark.parametrize("key", [b"q", b"\x1b", b"\x03"], ids=["q", "escape", "ctrl-c"])
def test_pty_cancel_restores_terminal(driver, key):
    rc, out = run_pty(driver, [_sess(1), _sess(2)], keys=[key])
    assert rc == cli.PICK_CANCELLED, out
    assert "attach cancelled" in out and f"RC {cli.PICK_CANCELLED} RESTORED" in out
    assert "EXEC" not in out and "Traceback" not in out


def test_pty_vanished_selection_is_a_friendly_error(driver):
    rc, out = run_pty(driver, [_sess(1), _sess(2)], keys=[b"2", b"\r"], vanish=["sess0002"])
    assert rc == 1, out
    assert "selected session is no longer available" in out and "RC 1 RESTORED" in out
    assert "EXEC" not in out and "Traceback" not in out


def test_pty_untrusted_title_is_not_emitted_raw(driver):
    evil = _sess(1, title="ok\x1b]0;pwned\x07\x1b[31mred\x1b[2J", cwd="/tmp/\x1b[1Gdir")
    rc, out = run_pty(driver, [evil], keys=[b"q"])
    assert rc == cli.PICK_CANCELLED
    assert "\x1b]0;" not in out and "\x1b[31m" not in out and "\x1b[2J" not in out and "\x1b[1G" not in out
    assert "okred" in out


def test_pty_small_terminal_scrolls_to_last_entry(driver):
    sessions = [_sess(i) for i in range(1, 11)]
    rc, out = run_pty(driver, sessions, keys=[b"\x1b[F", b"\r"], rows=6, cols=50)
    assert rc == 0 and "EXEC sess0010 RESTORED" in out
    assert "of 10)" in out


def test_pty_dumb_terminal_numbered_fallback(driver):
    rc, out = run_pty(driver, [_sess(1), _sess(2)], keys=[b"2\n"], wait_for=b"Attach to session [1-2]", TERM="dumb")
    assert rc == 0 and "EXEC sess0002" in out
    assert "\x1b[" not in out  # plain text only on a dumb terminal


def test_pty_dumb_terminal_cancel(driver):
    rc, out = run_pty(driver, [_sess(1)], keys=[b"\n"], wait_for=b"Attach to session [1-1]", TERM="dumb")
    assert rc == cli.PICK_CANCELLED and "attach cancelled" in out


def test_pty_explicit_id_bypasses_picker(driver):
    rc, out = run_pty(driver, [_sess(1), _sess(2)], args=["sess0002"], wait_for=b"EXEC")
    assert rc == 0 and "EXEC sess0002" in out and "choose a session" not in out


def test_pty_explicit_unknown_id_is_a_friendly_error(driver):
    rc, out = run_pty(driver, [_sess(1)], args=["nosuch01"], wait_for=b"RC 1")
    assert rc == 1 and "cannot attach to nosuch01" in out and "Traceback" not in out


def test_pty_no_sessions(driver):
    rc, out = run_pty(driver, [], wait_for=b"RC 2")
    assert rc == 2 and "no live managed sessions" in out and "choose a session" not in out


def test_pty_ignores_sessions_without_send_capability(driver):
    dead = _sess(2, capabilities={"send": False, "interrupt": False, "approve": False, "stop": False})
    rc, out = run_pty(driver, [_sess(1), dead], keys=[b"\r"])
    assert rc == 0 and "EXEC sess0001" in out and "sess0002" not in out and "Task 2" not in out


# ---------------------------------------------------------------- non-TTY keeps the old contract

def test_non_tty_single_session_attaches_automatically(driver):
    res = run_pipe(driver, [_sess(1)])
    assert res.returncode == 0 and "EXEC sess0001" in res.stdout


def test_non_tty_multiple_sessions_print_json_and_never_prompt(driver):
    res = run_pipe(driver, [_sess(1), _sess(2)])
    assert res.returncode == 2
    listed = json.loads(res.stdout[:res.stdout.index("RC 2")])
    assert [s["id"] for s in listed] == ["sess0001", "sess0002"]
    assert set(listed[0]) == {"id", "agent", "title", "status", "cwd"}
    assert "pass a session id" in res.stderr and "choose a session" not in res.stdout + res.stderr


def test_non_tty_no_sessions(driver):
    res = run_pipe(driver, [])
    assert res.returncode == 2 and res.stdout.startswith("[]") and "no live managed sessions" in res.stderr


def test_non_tty_vanished_single_session(driver):
    res = subprocess.run([sys.executable, str(driver)], capture_output=True, text=True, stdin=subprocess.DEVNULL,
                         env=_env([_sess(1)], vanish=["sess0001"]), timeout=20)
    assert res.returncode == 1 and "has ended" in res.stderr and "Traceback" not in res.stderr
