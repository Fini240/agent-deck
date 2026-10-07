"""Small shared helpers for provider discovery and terminal control.

Everything here is read-only with respect to the native agents' data.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

MAX_MESSAGE_CHARS = 8000
MAX_TOOL_CHARS = 1500
LAST_MESSAGE_CHARS = 240


def utc_iso(ts: float | None = None) -> str:
    if ts is None:
        ts = time.time()
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def parse_iso(value: Any) -> float | None:
    """Parse ISO8601 (with Z) or epoch seconds/ms into epoch seconds."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        v = float(value)
        return v / 1000.0 if v > 1e11 else v
    if isinstance(value, str):
        s = value.strip()
        if not s:
            return None
        try:
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            dt = datetime.fromisoformat(s)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.timestamp()
        except ValueError:
            return None
    return None


def norm_iso(value: Any, fallback: float | None = None) -> str:
    ts = parse_iso(value)
    return utc_iso(ts if ts is not None else fallback)


def claude_home() -> Path:
    # CLAUDE_CONFIG_DIR is Claude Code's own override (mirrors CODEX_HOME for Codex below)
    return Path(os.environ.get("AGENTDECK_CLAUDE_HOME") or os.environ.get("CLAUDE_CONFIG_DIR")
                or Path.home() / ".claude")


def codex_home() -> Path:
    return Path(os.environ.get("AGENTDECK_CODEX_HOME") or os.environ.get("CODEX_HOME") or Path.home() / ".codex")


def agentdeck_home() -> Path:
    try:
        from agentdeck.config import home_dir  # backend-owned

        return Path(home_dir())
    except Exception:
        return Path(os.environ.get("AGENTDECK_HOME") or Path.home() / ".agent-deck")


def is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (ValueError, OSError):
        return False


# --- bounded file reading -------------------------------------------------

def read_head_lines(path: Path, max_bytes: int) -> list[str]:
    with open(path, "rb") as fh:
        data = fh.read(max_bytes)
    lines = data.split(b"\n")
    if len(data) == max_bytes and lines:
        lines = lines[:-1]  # last line may be cut
    return [ln.decode("utf-8", "replace") for ln in lines if ln.strip()]


def read_tail_lines(path: Path, max_bytes: int) -> tuple[list[str], bool]:
    """Return (lines, reached_start). The first partial line is dropped."""
    size = path.stat().st_size
    start = max(0, size - max_bytes)
    with open(path, "rb") as fh:
        fh.seek(start)
        data = fh.read(max_bytes)
    lines = data.split(b"\n")
    if start > 0 and lines:
        lines = lines[1:]
    return [ln.decode("utf-8", "replace") for ln in lines if ln.strip()], start == 0


def iter_json_lines(lines: list[str]) -> Iterator[dict]:
    import json

    for ln in lines:
        try:
            obj = json.loads(ln)
        except (ValueError, RecursionError):
            continue
        if isinstance(obj, dict):
            yield obj


# --- text hygiene ------------------------------------------------------------

_REDACTIONS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"), "[redacted private key]"),
    (re.compile(r"\b(sk-(?:ant-|proj-)?[A-Za-z0-9_\-]{16,})"), "[redacted]"),
    (re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), "[redacted]"),
    (re.compile(r"\b(xox[abposr]-[A-Za-z0-9-]{10,})"), "[redacted]"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "[redacted]"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}"), "[redacted]"),
    (re.compile(r"\b(ya29\.[0-9A-Za-z_\-]{20,})"), "[redacted]"),
    (re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"), "[redacted jwt]"),
    (re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9._\-+/=]{16,}"), r"\1 [redacted]"),
    (
        re.compile(
            r"(?i)\b([A-Z0-9_]*(?:api[_-]?key|secret|token|passwd|password|private[_-]?key|auth)[A-Z0-9_]*)"
            r"(\s*[=:]\s*)(['\"]?)[^\s'\"]{8,}\3"
        ),
        r"\1\2\3[redacted]\3",
    ),
]


def redact(text: str) -> str:
    if not text:
        return text
    for pattern, repl in _REDACTIONS:
        text = pattern.sub(repl, text)
    return text


def clip(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 1)].rstrip() + "…"


def clean_text(text: Any, limit: int = MAX_MESSAGE_CHARS) -> str:
    if not isinstance(text, str):
        return ""
    return clip(redact(text.replace("\r\n", "\n")).strip(), limit)


# --- processes -------------------------------------------------------------

def pid_alive(pid: Any) -> bool:
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


# Linux: read-only procfs instead of ps/lsof (lsof is often missing in containers and needs
# no extra privileges here either). Tests point PROC_ROOT at a fake tree.
PROC_ROOT = Path("/proc")
MAX_PROC_ROWS = 20000
MAX_FDS_PER_PROCESS = 4096
NO_TTY = "??"  # macOS ps spelling; Linux ps prints "?", procfs rows use this constant


def procfs_available() -> bool:
    return sys.platform.startswith("linux") and (PROC_ROOT / "self" / "stat").exists()


def has_tty(row: dict) -> bool:
    return str(row.get("tty") or "") not in ("", "?", "??", "-")


def _tty_name(tty_nr: int) -> str:
    """Name of a controlling terminal from /proc/<pid>/stat ``tty_nr`` (ps-compatible, best effort)."""
    if tty_nr <= 0:
        return NO_TTY
    major = (tty_nr >> 8) & 0xFFF
    minor = (tty_nr & 0xFF) | ((tty_nr >> 12) & 0xFFF00)
    if 136 <= major <= 143:  # Unix98 pseudo terminals
        return f"pts/{(major - 136) * 256 + minor}"
    if major == 4:
        return f"tty{minor}" if minor < 64 else f"ttyS{minor - 64}"
    return f"tty({major},{minor})"


def _proc_stat(pid_dir: Path) -> tuple[int, int] | None:
    """(ppid, tty_nr) from /proc/<pid>/stat; the comm field may contain spaces and parens."""
    try:
        raw = (pid_dir / "stat").read_bytes()
    except OSError:
        return None
    end = raw.rfind(b")")
    if end < 0:
        return None
    fields = raw[end + 2:].split()
    try:
        return int(fields[1]), int(fields[4])  # state, ppid, pgrp, session, tty_nr
    except (IndexError, ValueError):
        return None


def _proc_command(pid_dir: Path) -> str:
    try:
        raw = (pid_dir / "cmdline").read_bytes()[:65536]
    except OSError:
        raw = b""
    args = [a.decode("utf-8", "replace") for a in raw.split(b"\0") if a]
    if args:
        return " ".join(args)
    try:  # kernel threads / zombies: ps shows [comm]
        return "[" + (pid_dir / "comm").read_text(errors="replace").strip() + "]"
    except OSError:
        return ""


def proc_rows() -> list[dict]:
    """Process table straight from procfs; vanished or unreadable processes are skipped."""
    rows: list[dict] = []
    try:
        entries = os.listdir(PROC_ROOT)
    except OSError:
        return rows
    for name in entries:
        if not name.isdigit():
            continue
        pid_dir = PROC_ROOT / name
        stat = _proc_stat(pid_dir)
        if stat is None:
            continue
        rows.append({"pid": int(name), "ppid": stat[0], "tty": _tty_name(stat[1]), "command": _proc_command(pid_dir)})
        if len(rows) >= MAX_PROC_ROWS:
            break
    return rows


def _ps_rows() -> list[dict]:
    rows: list[dict] = []
    try:
        out = subprocess.run(
            ["ps", "-axww", "-o", "pid=,ppid=,tty=,command="],
            capture_output=True, text=True, timeout=5, check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        out = ""
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) < 4:
            continue
        try:
            rows.append({"pid": int(parts[0]), "ppid": int(parts[1]), "tty": parts[2], "command": parts[3]})
        except ValueError:
            continue
    return rows


class _ProcCache:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._at = 0.0
        self._rows: list[dict] = []

    def rows(self, max_age: float = 2.0) -> list[dict]:
        with self._lock:
            if time.monotonic() - self._at < max_age:
                return self._rows
            rows = proc_rows() if procfs_available() else []
            if not rows:
                rows = _ps_rows()
            self._rows = rows
            self._at = time.monotonic()
            return rows


PROCS = _ProcCache()


def descendants(root_pid: int, rows: list[dict] | None = None) -> list[dict]:
    rows = rows if rows is not None else PROCS.rows()
    children: dict[int, list[dict]] = {}
    for r in rows:
        children.setdefault(r["ppid"], []).append(r)
    out: list[dict] = []
    stack = [root_pid]
    seen = set()
    while stack:
        pid = stack.pop()
        for child in children.get(pid, []):
            if child["pid"] in seen:
                continue
            seen.add(child["pid"])
            out.append(child)
            stack.append(child["pid"])
    return out


def _proc_pid_dir(pid: Any) -> Path | None:
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return None
    return PROC_ROOT / str(pid) if pid > 0 else None


def _proc_open_files(pid_dir: Path) -> list[str] | None:
    """Absolute paths behind /proc/<pid>/fd; None when procfs can't answer (gone / not permitted)."""
    fd_dir = pid_dir / "fd"
    try:
        names = os.listdir(fd_dir)
    except OSError:  # vanished, or another user's process without ptrace rights
        return None
    out: list[str] = []
    for name in names[:MAX_FDS_PER_PROCESS]:
        try:
            target = os.readlink(fd_dir / name)
        except OSError:
            continue
        # sockets/pipes/anon inodes are "socket:[123]" etc.; deleted files can't be read anyway
        if target.startswith("/") and not target.endswith(" (deleted)"):
            out.append(target)
    return out


def open_files(pid: int) -> list[str]:
    """Paths of files a process has open (procfs on Linux, else lsof; bounded, read-only)."""
    if procfs_available():
        pid_dir = _proc_pid_dir(pid)
        if pid_dir is None:
            return []
        found = _proc_open_files(pid_dir)
        if found is not None or not shutil.which("lsof"):
            return found or []
    try:
        out = subprocess.run(
            ["lsof", "-n", "-P", "-w", "-p", str(int(pid)), "-Fn"],
            capture_output=True, text=True, timeout=5, check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError, ValueError):
        return []
    return [ln[1:] for ln in out.splitlines() if ln.startswith("n/")]


def process_cwd(pid: int) -> str | None:
    if procfs_available():
        pid_dir = _proc_pid_dir(pid)
        if pid_dir is None:
            return None
        try:
            target = os.readlink(pid_dir / "cwd")
        except OSError:  # vanished or not permitted; lsof could not do better
            return None
        return target if target.startswith("/") and not target.endswith(" (deleted)") else None
    try:
        out = subprocess.run(
            ["lsof", "-n", "-P", "-w", "-a", "-p", str(int(pid)), "-d", "cwd", "-Fn"],
            capture_output=True, text=True, timeout=5, check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError, ValueError):
        return None
    for ln in out.splitlines():
        if ln.startswith("n/"):
            return ln[1:]
    return None


class TTLCache:
    """Tiny thread-safe memo keyed by arbitrary hashable keys."""

    def __init__(self, max_items: int = 512) -> None:
        self._lock = threading.Lock()
        self._data: dict[Any, tuple[Any, Any]] = {}
        self._max = max_items

    def get(self, key: Any, stamp: Any) -> Any:
        with self._lock:
            hit = self._data.get(key)
            if hit and hit[0] == stamp:
                return hit[1]
        return None

    def put(self, key: Any, stamp: Any, value: Any) -> None:
        with self._lock:
            if len(self._data) >= self._max:
                self._data.pop(next(iter(self._data)))
            self._data[key] = (stamp, value)
