"""Private on-disk storage: 0700 directories, 0600 files, atomic writes, file locks."""

from __future__ import annotations

import contextlib
import json
import os
import tempfile
import threading
from pathlib import Path
from typing import Any, Iterator

try:  # POSIX
    import fcntl
except ImportError:  # pragma: no cover - Windows
    fcntl = None  # type: ignore[assignment]

_thread_locks: dict[str, threading.RLock] = {}
_thread_locks_guard = threading.Lock()


def ensure_private_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    if os.name == "posix":
        os.chmod(path, 0o700)
    return path


def write_private_bytes(path: Path, data: bytes) -> None:
    """Atomically replace ``path`` with ``data``; the file is never group/world readable."""
    ensure_private_dir(path.parent)
    fd, tmp = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        if os.name == "posix":
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(tmp)
        raise


def write_private_json(path: Path, value: Any) -> None:
    write_private_bytes(path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode())


def read_json(path: Path, default: Any) -> Any:
    try:
        with open(path, "rb") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return default
    except (json.JSONDecodeError, UnicodeDecodeError):
        # Keep the corrupt file for inspection instead of silently losing it.
        with contextlib.suppress(OSError):
            os.replace(path, path.with_suffix(path.suffix + ".corrupt"))
        return default


@contextlib.contextmanager
def locked(path: Path) -> Iterator[None]:
    """Exclusive lock shared by threads in this process and by other processes (admin CLI)."""
    key = str(path)
    with _thread_locks_guard:
        tlock = _thread_locks.setdefault(key, threading.RLock())
    with tlock:
        ensure_private_dir(path.parent)
        lock_path = path.with_name(f".{path.name}.lock")
        fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            if fcntl is not None:
                fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            if fcntl is not None:
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)


def is_private_file(path: Path) -> bool:
    if os.name != "posix":
        return True
    return (path.stat().st_mode & 0o077) == 0


class BoundedJsonl:
    """Append-only JSONL log capped at ``max_bytes`` with one rotated generation."""

    def __init__(self, path: Path, max_bytes: int = 1_000_000):
        self.path = path
        self.max_bytes = max_bytes
        self._lock = threading.Lock()

    def append(self, record: dict) -> None:
        line = (json.dumps(record, sort_keys=True) + "\n").encode()
        with self._lock:
            ensure_private_dir(self.path.parent)
            try:
                size = self.path.stat().st_size
            except FileNotFoundError:
                size = 0
            if size + len(line) > self.max_bytes:
                os.replace(self.path, self.path.with_suffix(self.path.suffix + ".1"))
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            with os.fdopen(fd, "ab") as fh:
                fh.write(line)
