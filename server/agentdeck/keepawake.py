"""Sleep assertion held only while monitored agents are working.

macOS: ``caffeinate -i`` (``-s`` for plugged_in mode) with ``-w <our pid>`` so the
assertion disappears even if this server is killed. The display may still sleep.
Linux: ``systemd-inhibit --what=idle:sleep`` around ``sleep infinity``.
Only the child process this module started is ever terminated.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import threading
from typing import Callable

log = logging.getLogger("agentdeck.keepawake")


def default_command(mode: str) -> list[str] | None:
    if mode == "off":
        return None
    if sys.platform == "darwin":
        exe = "/usr/bin/caffeinate" if os.path.exists("/usr/bin/caffeinate") else shutil.which("caffeinate")
        if not exe:
            return None
        flag = "-s" if mode == "plugged_in" else "-i"
        return [exe, flag, "-w", str(os.getpid())]
    if sys.platform.startswith("linux"):
        inhibit = shutil.which("systemd-inhibit")
        sleep = shutil.which("sleep")
        if not inhibit or not sleep:
            return None
        return [inhibit, "--what=idle:sleep", "--who=agent-deck", "--why=AI agent working", "--mode=block", sleep, "infinity"]
    return None


class KeepAwake:
    def __init__(self, command_for_mode: Callable[[str], list[str] | None] = default_command):
        self.command_for_mode = command_for_mode
        self._proc: subprocess.Popen | None = None
        self._mode: str | None = None
        self._lock = threading.Lock()
        self.last_error: str | None = None

    @property
    def held(self) -> bool:
        with self._lock:
            return self._proc is not None and self._proc.poll() is None

    def status(self) -> dict:
        return {"held": self.held, "mode": self._mode, "error": self.last_error}

    def update(self, active: bool, mode: str) -> None:
        """Idempotently hold or release the assertion."""
        with self._lock:
            alive = self._proc is not None and self._proc.poll() is None
            if active and mode != "off":
                if alive and self._mode == mode:
                    return
                self._release_locked()
                cmd = self.command_for_mode(mode)
                if not cmd:
                    self.last_error = "sleep prevention unsupported on this platform"
                    return
                try:
                    self._proc = subprocess.Popen(
                        cmd,
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        start_new_session=True,
                    )
                    self._mode = mode
                    self.last_error = None
                    log.info("keep-awake held (%s)", mode)
                except OSError as exc:
                    self.last_error = f"could not start sleep assertion ({type(exc).__name__})"
                    self._proc = None
            else:
                self._release_locked()

    def release(self) -> None:
        with self._lock:
            self._release_locked()

    def _release_locked(self) -> None:
        proc, self._proc = self._proc, None
        self._mode = None
        if proc is None:
            return
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=3)
            log.info("keep-awake released")
