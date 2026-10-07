"""Linux process discovery through read-only procfs (fake /proc tree; real /proc when on Linux)."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

from agentdeck.providers import codex, common

THREAD = "0199aaaa-bbbb-7ccc-8ddd-eeeeffff0001"


def tty_nr(major: int, minor: int) -> int:
    return ((minor & 0xFFF00) << 12) | ((major & 0xFFF) << 8) | (minor & 0xFF)


class FakeProc:
    """Minimal /proc: <pid>/{stat,cmdline,comm,cwd,fd/*} plus self/stat."""

    def __init__(self, root: Path):
        self.root = root
        (root / "self").mkdir(parents=True)
        (root / "self" / "stat").write_text("1 (init) S 0 1 1 0 -1\n")
        (root / "sys").mkdir()  # non-numeric entries must be ignored

    def add(self, pid: int, ppid: int, argv: list[str], *, comm: str = "proc", tty: int = 0,
            cwd: Path | None = None, fds: dict[str, str] | None = None) -> Path:
        d = self.root / str(pid)
        d.mkdir()
        (d / "stat").write_text(f"{pid} ({comm}) S {ppid} {pid} {pid} {tty} -1 4194560 0 0\n")
        (d / "cmdline").write_bytes(b"".join(a.encode() + b"\0" for a in argv))
        (d / "comm").write_text(comm + "\n")
        if cwd is not None:
            os.symlink(cwd, d / "cwd")
        (d / "fd").mkdir()
        for name, target in (fds or {}).items():
            os.symlink(target, d / "fd" / name)
        return d


@pytest.fixture
def fakeproc(tmp_path, monkeypatch):
    fp = FakeProc(tmp_path / "proc")
    monkeypatch.setattr(common, "PROC_ROOT", fp.root)
    monkeypatch.setattr(common.sys, "platform", "linux")
    # any subprocess (ps/lsof) use while procfs answers is a regression
    calls: list[list[str]] = []

    def no_subprocess(cmd, *a, **k):
        calls.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr(common.subprocess, "run", no_subprocess)
    fp.calls = calls
    common.PROCS._at = 0.0
    yield fp
    common.PROCS._at = 0.0


def test_tty_names_match_ps():
    assert common._tty_name(0) == "??"
    assert common._tty_name(tty_nr(136, 3)) == "pts/3"
    assert common._tty_name(tty_nr(136, 300)) == "pts/300"  # minor > 255 uses the high minor bits
    assert common._tty_name(tty_nr(137, 1)) == "pts/257"
    assert common._tty_name(tty_nr(4, 1)) == "tty1"
    assert common._tty_name(tty_nr(4, 64)) == "ttyS0"


def test_has_tty_treats_linux_and_mac_no_tty_spellings_alike():
    assert not common.has_tty({"tty": "?"})  # Linux ps
    assert not common.has_tty({"tty": "??"})  # macOS ps / procfs rows
    assert not common.has_tty({"tty": "-"})
    assert not common.has_tty({"tty": ""})
    assert not common.has_tty({})
    assert common.has_tty({"tty": "pts/0"})
    assert common.has_tty({"tty": "ttys004"})


def test_procfs_available_only_on_linux_with_proc(tmp_path, monkeypatch):
    monkeypatch.setattr(common, "PROC_ROOT", tmp_path / "missing")
    monkeypatch.setattr(common.sys, "platform", "linux")
    assert not common.procfs_available()
    (tmp_path / "missing" / "self").mkdir(parents=True)
    (tmp_path / "missing" / "self" / "stat").write_text("1 (x) S 0\n")
    assert common.procfs_available()
    monkeypatch.setattr(common.sys, "platform", "darwin")
    assert not common.procfs_available()


def test_proc_rows_parse_stat_cmdline_and_tty(fakeproc):
    fakeproc.add(100, 1, ["/usr/bin/bash"], comm="bash", tty=tty_nr(136, 2))
    # comm with spaces and parentheses must not shift the stat fields
    fakeproc.add(101, 100, ["node", "/usr/lib/node_modules/@openai/codex/bin/codex.js", "resume"],
                 comm="weird (name) x", tty=tty_nr(136, 2))
    fakeproc.add(2, 0, [], comm="kthreadd")  # kernel thread: empty cmdline
    (fakeproc.root / "555").mkdir()  # vanished between listdir and read: no stat file
    rows = {r["pid"]: r for r in common.PROCS.rows(max_age=0)}
    assert set(rows) == {100, 101, 2}
    assert rows[101] == {"pid": 101, "ppid": 100, "tty": "pts/2",
                         "command": "node /usr/lib/node_modules/@openai/codex/bin/codex.js resume"}
    assert rows[2]["command"] == "[kthreadd]" and rows[2]["tty"] == "??"
    assert [r["pid"] for r in common.descendants(100, list(rows.values()))] == [101]
    assert fakeproc.calls == []  # no ps


def test_proc_rows_fall_back_to_ps_when_procfs_empty(fakeproc, monkeypatch):
    out = "  7     1 ttys001  /usr/bin/codex\n"
    monkeypatch.setattr(common.subprocess, "run",
                        lambda cmd, *a, **k: subprocess.CompletedProcess(cmd, 0, stdout=out, stderr=""))
    assert common.PROCS.rows(max_age=0) == [{"pid": 7, "ppid": 1, "tty": "ttys001", "command": "/usr/bin/codex"}]


def test_open_files_and_cwd_from_procfs(fakeproc, tmp_path):
    work = tmp_path / "work"
    work.mkdir()
    rollout = tmp_path / "sessions" / f"rollout-2026-10-07T10-00-00-{THREAD}.jsonl"
    rollout.parent.mkdir()
    rollout.write_text("{}\n")
    fakeproc.add(200, 1, ["codex"], cwd=work, fds={
        "0": "/dev/pts/4", "3": str(rollout), "4": "socket:[12345]", "5": "pipe:[99]",
        "6": "anon_inode:[eventfd]", "7": str(tmp_path / "gone.txt") + " (deleted)",
    })
    files = common.open_files(200)
    assert sorted(files) == sorted(["/dev/pts/4", str(rollout)])
    assert common.process_cwd(200) == str(work)
    assert codex.thread_for_pids([200]) == THREAD
    assert fakeproc.calls == []  # no lsof


def test_vanished_and_invalid_pids_are_empty_not_errors(fakeproc, monkeypatch):
    monkeypatch.setattr(common.shutil, "which", lambda name: None)
    for pid in (999999, 0, -1, "abc", None):
        assert common.open_files(pid) == []
        assert common.process_cwd(pid) is None
    assert fakeproc.calls == []


@pytest.mark.skipif(hasattr(os, "geteuid") and os.geteuid() == 0, reason="root ignores directory permissions")
def test_permission_denied_fd_dir_is_empty_and_never_raises(fakeproc, tmp_path, monkeypatch):
    d = fakeproc.add(300, 1, ["codex"], cwd=tmp_path, fds={"3": "/etc/hosts"})
    os.chmod(d / "fd", 0)
    os.chmod(d, 0o500)  # readlink of cwd still works; fd listing does not
    try:
        monkeypatch.setattr(common.shutil, "which", lambda name: None)
        assert common.open_files(300) == []
        # with lsof installed the old macOS path is still tried (it can't see more either)
        monkeypatch.setattr(common.shutil, "which", lambda name: "/usr/bin/lsof")
        assert common.open_files(300) == []
        assert any(c[0] == "lsof" for c in fakeproc.calls)
        # cwd of a process we may not inspect
        os.chmod(d, 0)
        assert common.process_cwd(300) is None
    finally:
        os.chmod(d, 0o700)
        os.chmod(d / "fd", 0o700)


def test_macos_keeps_lsof_path(tmp_path, monkeypatch):
    monkeypatch.setattr(common.sys, "platform", "darwin")
    seen: list[list[str]] = []

    def fake_run(cmd, *a, **k):
        seen.append(list(cmd))
        stdout = "p1\nfcwd\nn/Users/x/work\n" if "-d" in cmd else "p1\nn/Users/x/a.jsonl\nf3\n"
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(common.subprocess, "run", fake_run)
    assert common.open_files(1) == ["/Users/x/a.jsonl"]
    assert common.process_cwd(1) == "/Users/x/work"
    assert all(c[0] == "lsof" for c in seen)


def test_codex_live_processes_ignore_linux_no_tty_rows(fakeproc, tmp_path, monkeypatch):
    rollout = tmp_path / f"rollout-2026-10-07T10-00-00-{THREAD}.jsonl"
    rollout.write_text("{}\n")
    work = tmp_path / "w"
    work.mkdir()
    # terminal codex with an open rollout
    fakeproc.add(400, 1, ["/home/u/.local/bin/codex"], tty=tty_nr(136, 1), fds={"9": str(rollout)})
    # interactive-looking codex WITHOUT a terminal (e.g. a background container worker): not a TUI
    fakeproc.add(401, 1, ["/usr/local/bin/codex"], tty=0, cwd=work)
    # terminal codex that has no rollout yet -> unmapped with its cwd
    fakeproc.add(402, 1, ["/usr/local/bin/codex"], tty=tty_nr(136, 5), cwd=work)
    monkeypatch.setattr(codex, "_live_cache", {"at": 0.0, "value": None})
    live = codex.live_processes(max_age=0)
    assert live["byThread"] == {THREAD: {"pid": 400, "how": "open-rollout-or-args"}}
    assert live["unmapped"] == [{"pid": 402, "cwd": str(work)}]


def test_claude_home_respects_explicit_homes(tmp_path, monkeypatch):
    monkeypatch.delenv("AGENTDECK_CLAUDE_HOME", raising=False)
    monkeypatch.delenv("CLAUDE_CONFIG_DIR", raising=False)
    assert common.claude_home() == Path.home() / ".claude"
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path / "cfg"))
    assert common.claude_home() == tmp_path / "cfg"
    monkeypatch.setenv("AGENTDECK_CLAUDE_HOME", str(tmp_path / "explicit"))
    assert common.claude_home() == tmp_path / "explicit"
    monkeypatch.delenv("AGENTDECK_CODEX_HOME", raising=False)
    monkeypatch.setenv("CODEX_HOME", str(tmp_path / "cx"))
    assert common.codex_home() == tmp_path / "cx"
    monkeypatch.setenv("AGENTDECK_CODEX_HOME", str(tmp_path / "cx2"))
    assert common.codex_home() == tmp_path / "cx2"


@pytest.mark.skipif(not common.procfs_available(), reason="real Linux procfs only")
def test_real_procfs_matches_this_process(tmp_path):
    common.PROCS._at = 0.0
    rows = {r["pid"]: r for r in common.PROCS.rows(max_age=0)}
    me = rows[os.getpid()]
    assert me["ppid"] == os.getppid()
    assert "pytest" in me["command"] or "python" in me["command"]
    marker = tmp_path / "marker.txt"
    with open(marker, "w") as fh:
        fh.write("x")
        fh.flush()
        assert str(marker.resolve()) in common.open_files(os.getpid())
    assert common.process_cwd(os.getpid()) == os.path.realpath(os.getcwd())
    assert common.pid_alive(os.getpid())
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
    try:
        common.PROCS._at = 0.0
        kids = common.descendants(os.getpid(), common.PROCS.rows(max_age=0))
        assert child.pid in [k["pid"] for k in kids]
    finally:
        child.kill()
        child.wait()
    assert not common.pid_alive(child.pid)
