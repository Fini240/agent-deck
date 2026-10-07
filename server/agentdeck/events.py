"""In-process event bus feeding the SSE endpoint."""

from __future__ import annotations

import asyncio
import json
import threading
from typing import Any


CLOSE = None  # queued to tell a stream to end


class EventBus:
    def __init__(self, max_queue: int = 100):
        self.max_queue = max_queue
        self._subs: set[asyncio.Queue] = set()
        self._owners: dict[asyncio.Queue, str] = {}
        self._loop: asyncio.AbstractEventLoop | None = None
        self._lock = threading.Lock()

    def bind_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    @property
    def subscriber_count(self) -> int:
        return len(self._subs)

    def subscribe(self, owner: str | None = None, max_per_owner: int | None = None) -> asyncio.Queue:
        """Add a stream. With ``max_per_owner`` the owner's oldest streams are closed
        first, so a phone that reconnects without its old connection being torn down
        replaces its own stale streams instead of locking everyone out."""
        q: asyncio.Queue = asyncio.Queue(maxsize=self.max_queue)
        with self._lock:
            if owner is not None and max_per_owner is not None:
                mine = [s for s, o in self._owners.items() if o == owner]  # insertion order = oldest first
                for old in mine[: max(0, len(mine) - max_per_owner + 1)]:
                    self._close_locked(old)
            self._subs.add(q)
            if owner is not None:
                self._owners[q] = owner
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        with self._lock:
            self._subs.discard(q)
            self._owners.pop(q, None)

    def close_owner(self, owner: str) -> None:
        """End every stream of ``owner`` (e.g. a revoked device). Call on the loop thread."""
        with self._lock:
            for q in [s for s, o in self._owners.items() if o == owner]:
                self._close_locked(q)

    def _close_locked(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)
        self._owners.pop(q, None)
        while q.full():
            q.get_nowait()
        q.put_nowait(CLOSE)

    def _put_all(self, message: str) -> None:
        with self._lock:
            subs = list(self._subs)
        for q in subs:
            if q.full():
                # Slow client: drop the oldest event; clients re-fetch on any update anyway.
                try:
                    q.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            q.put_nowait(message)

    def publish(self, type_: str, session_id: str | None = None, data: dict[str, Any] | None = None) -> None:
        """Thread-safe publish of ``{type, sessionId?, data?}``."""
        event: dict[str, Any] = {"type": type_}
        if session_id is not None:
            event["sessionId"] = session_id
        if data is not None:
            event["data"] = data
        message = json.dumps(event, separators=(",", ":"))
        loop = self._loop
        if loop is None or loop.is_closed():
            return
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is loop:
            self._put_all(message)
        else:
            loop.call_soon_threadsafe(self._put_all, message)


def format_sse(data: str, event: str = "update") -> str:
    return f"event: {event}\ndata: {data}\n\n"
