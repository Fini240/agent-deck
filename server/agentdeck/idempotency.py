"""Request-ID idempotency for control actions (send/stop/approve/input/start).

Results are kept in memory and persisted (bounded, 24 h) so a WorkManager retry
after a server restart still does not send the same reply twice.
"""

from __future__ import annotations

import threading
import time
from collections import OrderedDict
from pathlib import Path
from typing import Any, Callable

from .errors import AgentDeckError, DeliveryUncertainError, UnavailableError
from .storage import read_json, write_private_json

MAX_ENTRIES = 1000
TTL_SECONDS = 24 * 3600
_ERROR_KEY = "__agentdeck_error__"


def _replay(result: Any) -> Any:
    if isinstance(result, dict) and isinstance(result.get(_ERROR_KEY), dict):
        e = result[_ERROR_KEY]
        err = AgentDeckError(str(e.get("message") or ""), code=str(e.get("code") or "internal_error"),
                             status=int(e.get("status") or 500))
        err.uncertain = True
        raise err
    return result


class IdempotencyStore:
    def __init__(self, path: Path | None, clock: Callable[[], float] = time.time):
        self.path = path
        self.clock = clock
        self._done: OrderedDict[str, tuple[float, Any]] = OrderedDict()
        self._inflight: dict[str, threading.Event] = {}
        self._lock = threading.Lock()
        if path is not None:
            raw = read_json(path, {})
            now = clock()
            for key, entry in (raw.get("entries", {}) if isinstance(raw, dict) else {}).items():
                if isinstance(entry, list) and len(entry) == 2 and now - entry[0] < TTL_SECONDS:
                    self._done[key] = (entry[0], entry[1])

    def _persist(self) -> None:
        if self.path is not None:
            write_private_json(self.path, {"entries": {k: [t, r] for k, (t, r) in self._done.items()}})

    def run(self, key: str, fn: Callable[[], Any]) -> Any:
        """Run ``fn`` once per key. Concurrent duplicates wait for the first result.

        Failures are not cached, so a retry after an error is attempted again,
        except errors flagged ``uncertain`` (the action may already have taken
        effect): those are replayed for the same key instead of re-running ``fn``.
        """
        while True:
            with self._lock:
                event = self._inflight.get(key)
                if event is None:
                    hit = self._done.get(key)
                    if hit is not None and self.clock() - hit[0] < TTL_SECONDS:
                        return _replay(hit[1])
                    event = threading.Event()
                    self._inflight[key] = event
                    # Journal BEFORE touching the terminal. A crash after Enter but before
                    # confirmation must not allow the same reply to be typed a second time.
                    pending = DeliveryUncertainError("Delivery may have happened before the helper restarted. Check the chat before sending another message.")
                    self._done[key] = (self.clock(), {_ERROR_KEY: {"code": pending.code, "message": pending.message, "status": pending.status}})
                    try:
                        self._persist()
                    except OSError:
                        self._done.pop(key, None)
                        self._inflight.pop(key, None)
                        event.set()
                        raise UnavailableError("Could not record this request safely; nothing was sent.") from None
                    break
            event.wait(timeout=120)
        try:
            result = fn()
        except BaseException as exc:
            if isinstance(exc, AgentDeckError) and (getattr(exc, "uncertain", False) or getattr(exc, "retry_safe", True) is False):
                self._store(key, {_ERROR_KEY: {"code": exc.code, "message": exc.message, "status": exc.status}}, event)
                raise
            with self._lock:
                # Recognized validation/readiness errors occur before submission and can
                # be retried. Keep the journal for unexpected failures or process death.
                if isinstance(exc, AgentDeckError) and exc.status < 500:
                    self._done.pop(key, None)
                    try:
                        self._persist()
                    except OSError:
                        pass
                self._inflight.pop(key, None)
            event.set()
            raise
        self._store(key, result, event)
        return result

    def _store(self, key: str, result: Any, event: threading.Event) -> None:
        with self._lock:
            self._done[key] = (self.clock(), result)
            self._done.move_to_end(key)
            while len(self._done) > MAX_ENTRIES:
                self._done.popitem(last=False)
            self._inflight.pop(key, None)
            try:
                self._persist()
            except OSError:
                pass
        event.set()
