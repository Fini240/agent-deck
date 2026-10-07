"""Live model discovery from the installed, already-authenticated CLIs.

Claude Code: ``claude -p --input-format stream-json --output-format stream-json``
receives a single SDK ``initialize`` control request and answers with the
account's model list. No user message is sent, so no inference happens. User
hooks/MCP servers are skipped (``--setting-sources local --strict-mcp-config``)
and nothing is persisted (``--no-session-persistence``).

Codex: ``codex app-server`` JSON-RPC ``initialize`` then paginated ``model/list``.

Model IDs are passed through exactly as the CLIs report them. If live discovery
fails, the last successful result is returned and labelled ``cache``.
"""

from __future__ import annotations

import json
import os
import select
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Callable

from .common import agentdeck_home, utc_iso

DISCOVERY_TIMEOUT = 45.0
MEMORY_TTL = 600.0
_lock = threading.Lock()
_memory: dict[str, Any] = {"at": 0.0, "value": None}


def agent_binary(agent: str) -> str | None:
    env = os.environ.get(f"AGENTDECK_{agent.upper()}_BIN")
    if env:
        return env if os.access(env, os.X_OK) else None
    found = shutil.which(agent)
    if found:
        return found
    cand = Path.home() / ".local" / "bin" / agent
    return str(cand) if os.access(cand, os.X_OK) else None


def _clean_env() -> dict[str, str]:
    env = dict(os.environ)
    for k in ("PYTHONPATH", "TMUX", "TMUX_PANE", "AGENTDECK_SESSION_ID", "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT"):
        env.pop(k, None)
    return env


def cli_version(binary: str) -> str | None:
    try:
        out = subprocess.run([binary, "--version"], capture_output=True, text=True, timeout=20,
                             env=_clean_env(), stdin=subprocess.DEVNULL).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return out.splitlines()[0][:80] if out else None


class _JsonLines:
    """Line-oriented JSON reader over a subprocess stdout with an overall deadline."""

    def __init__(self, proc: subprocess.Popen, deadline: float) -> None:
        self.proc = proc
        self.deadline = deadline
        self.buf = b""

    def next(self) -> dict | None:
        fd = self.proc.stdout.fileno()  # type: ignore[union-attr]
        while True:
            if b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except ValueError:
                    continue
                if isinstance(obj, dict):
                    return obj
                continue
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("model discovery timed out")
            r, _, _ = select.select([fd], [], [], min(remaining, 1.0))
            if not r:
                continue
            chunk = os.read(fd, 65536)
            if not chunk:
                return None
            if len(self.buf) > 32 * 1024 * 1024:
                raise ValueError("discovery output too large")
            self.buf += chunk


def _run_protocol(argv: list[str], fn: Callable[[subprocess.Popen, _JsonLines], Any], timeout: float) -> Any:
    workdir = tempfile.mkdtemp(prefix="agentdeck-models-")
    proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            cwd=workdir, env=_clean_env(), start_new_session=True)
    try:
        return fn(proc, _JsonLines(proc, time.monotonic() + timeout))
    finally:
        try:
            if proc.stdin:
                proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        shutil.rmtree(workdir, ignore_errors=True)


def _send(proc: subprocess.Popen, obj: dict) -> None:
    assert proc.stdin is not None
    proc.stdin.write((json.dumps(obj) + "\n").encode())
    proc.stdin.flush()


# --- Claude ---------------------------------------------------------------------

def claude_models(binary: str, timeout: float = DISCOVERY_TIMEOUT) -> list[dict]:
    argv = [binary, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--setting-sources", "local", "--strict-mcp-config", "--no-session-persistence"]

    def talk(proc: subprocess.Popen, rd: _JsonLines) -> list[dict]:
        _send(proc, {"type": "control_request", "request_id": "agentdeck-init",
                     "request": {"subtype": "initialize"}})
        while True:
            msg = rd.next()
            if msg is None:
                raise RuntimeError("claude exited before answering initialize")
            if msg.get("type") != "control_response":
                continue
            resp = msg.get("response") or {}
            if resp.get("request_id") not in (None, "agentdeck-init"):
                continue
            if resp.get("subtype") == "error":
                raise RuntimeError(str(resp.get("error") or "initialize failed")[:200])
            models = (resp.get("response") or {}).get("models")
            if not isinstance(models, list):
                raise RuntimeError("initialize response has no model list")
            return models

    raw = _run_protocol(argv, talk, timeout)
    out: list[dict] = []
    for m in raw:
        if not isinstance(m, dict) or not isinstance(m.get("value"), str):
            continue
        resolved = m.get("resolvedModel") if isinstance(m.get("resolvedModel"), str) else None
        desc = m.get("description") if isinstance(m.get("description"), str) else None
        if resolved and resolved != m["value"]:
            desc = f"{desc} · {resolved}" if desc else resolved
        out.append({
            "id": m["value"],
            "label": m.get("displayName") or m["value"],
            "description": desc,
            "source": "claude-cli-initialize",
            "resolvedModel": resolved,
            "isDefault": m["value"] == "default",
            "efforts": m.get("supportedEffortLevels") or [],
        })
    return out


# --- Codex -----------------------------------------------------------------------

def codex_models(binary: str, timeout: float = DISCOVERY_TIMEOUT) -> list[dict]:
    argv = [binary, "app-server"]

    def talk(proc: subprocess.Popen, rd: _JsonLines) -> list[dict]:
        def call(req_id: int, method: str, params: dict) -> dict:
            _send(proc, {"id": req_id, "method": method, "params": params})
            while True:
                msg = rd.next()
                if msg is None:
                    raise RuntimeError("codex app-server exited early")
                if msg.get("id") == req_id and "method" not in msg:
                    if msg.get("error"):
                        err = msg["error"]
                        raise RuntimeError(str(err.get("message") if isinstance(err, dict) else err)[:200])
                    return msg.get("result") or {}
                if "method" in msg and "id" in msg:
                    # server->client request we don't support: refuse politely
                    _send(proc, {"id": msg["id"], "error": {"code": -32601, "message": "unsupported"}})

        from agentdeck import __version__ as ver

        call(1, "initialize", {"clientInfo": {"name": "agent-deck", "title": "Agent Deck", "version": ver}})
        _send(proc, {"method": "initialized"})
        models: list[dict] = []
        cursor = None
        for page in range(20):
            params: dict[str, Any] = {}
            if cursor:
                params["cursor"] = cursor
            res = call(2 + page, "model/list", params)
            models.extend(m for m in res.get("data") or [] if isinstance(m, dict))
            cursor = res.get("nextCursor")
            if not cursor:
                break
        return models

    raw = _run_protocol(argv, talk, timeout)
    out: list[dict] = []
    for m in raw:
        mid = m.get("model") or m.get("id")
        if not isinstance(mid, str) or m.get("hidden"):
            continue
        efforts = [e.get("reasoningEffort") for e in m.get("supportedReasoningEfforts") or []
                   if isinstance(e, dict) and e.get("reasoningEffort")]
        out.append({
            "id": mid,
            "label": m.get("displayName") or mid,
            "description": m.get("description") or None,
            "source": "codex-app-server-model-list",
            "isDefault": bool(m.get("isDefault")),
            "efforts": efforts,
        })
    return out


# --- aggregate ------------------------------------------------------------------

AGENTS = {
    "claude": ("Claude Code", claude_models),
    "codex": ("Codex", codex_models),
}


def _cache_path() -> Path:
    return agentdeck_home() / "provider-cache" / "models.json"


def _load_cache() -> dict:
    try:
        data = json.loads(_cache_path().read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_cache(per_agent: dict[str, dict]) -> None:
    path = _cache_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(path.parent, 0o700)
        tmp = path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(per_agent, fh)
        os.replace(tmp, path)
    except OSError:
        pass


def _discover_agent(agent: str) -> dict:
    name, fn = AGENTS[agent]
    binary = agent_binary(agent)
    entry: dict[str, Any] = {"id": agent, "name": name, "available": False, "version": None, "models": [],
                             "modelSource": "none", "modelRefreshedAt": None, "error": None}
    if not binary:
        entry["error"] = f"{agent} CLI not found"
        return entry
    entry["available"] = True
    entry["version"] = cli_version(binary)
    try:
        entry["models"] = fn(binary)
        entry["modelSource"] = "live"
        entry["modelRefreshedAt"] = utc_iso()
        if not entry["models"]:
            entry["error"] = "CLI reported no models"
    except Exception as exc:  # noqa: BLE001 - surface a phone-safe reason
        entry["error"] = f"live discovery failed: {type(exc).__name__}: {str(exc)[:160]}"
    return entry


def discover_models(refresh: bool = False, agents: tuple[str, ...] = ("claude", "codex")) -> dict:
    with _lock:
        if not refresh and _memory["value"] is not None and time.monotonic() - _memory["at"] < MEMORY_TTL:
            return _memory["value"]
        results: dict[str, dict] = {}
        threads = [threading.Thread(target=lambda a=a: results.__setitem__(a, _discover_agent(a)), daemon=True)
                   for a in agents]
        for t in threads:
            t.start()
        for t in threads:
            t.join(DISCOVERY_TIMEOUT + 30)
        cache = _load_cache()
        fresh_cache = dict(cache)
        out_agents = []
        for a in agents:
            entry = results.get(a) or {"id": a, "name": AGENTS[a][0], "available": False, "version": None,
                                       "models": [], "modelSource": "none", "modelRefreshedAt": None,
                                       "error": "discovery did not finish"}
            if entry["modelSource"] == "live" and entry["models"]:
                fresh_cache[a] = {"models": entry["models"], "modelRefreshedAt": entry["modelRefreshedAt"],
                                  "version": entry["version"]}
            elif cache.get(a, {}).get("models"):
                entry["models"] = [dict(m, source=f"cache:{m.get('source')}") for m in cache[a]["models"]]
                entry["modelSource"] = "cache"
                entry["modelRefreshedAt"] = cache[a].get("modelRefreshedAt")
            out_agents.append(entry)
        if fresh_cache != cache:
            _save_cache(fresh_cache)
        value = {"agents": out_agents, "refreshedAt": utc_iso()}
        _memory.update(at=time.monotonic(), value=value)
        return value
