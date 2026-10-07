"""Agent Deck command line (local Mac side).

    python -m agentdeck.cli run claude|codex [native args...]   run the real TUI in a managed tmux pane and attach
    python -m agentdeck.cli attach [SESSION_ID]                  re-attach this terminal to a managed session
        (no id on a terminal: pick from a menu; without a TTY: attach the only session or list them as JSON)
    python -m agentdeck.cli list                                 managed sessions (JSON)
    python -m agentdeck.cli notify --kind progress --title T --body B [--current N --total M --unit U] [--stage S]
    python -m agentdeck.cli hook claude|codex                    Claude/Codex hook adapter -> notify (never fails the agent)
    python -m agentdeck.cli pair [...]                           delegates to agentdeck.admin pair
    python -m agentdeck.cli install-shell TARGET [--dry-run] [--uninstall]
    python -m agentdeck.cli sessions | models [--refresh]        discovery debug output (no transcript text)

``run`` never blocks normal use: non-interactive invocations (``claude -p``, ``codex exec``,
``--version``, subcommands, no TTY), ``AGENTDECK_DISABLE=1`` and nested calls inside a managed
pane exec the native binary directly. Any failure to create the managed pane also falls back
to the native binary.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import sys
from pathlib import Path

NATIVE_DEFAULTS = {agent: str(Path.home() / ".local" / "bin" / agent) for agent in ("claude", "codex")}
CLAUDE_SUBCOMMANDS = {"agents", "attach", "auth", "auto-mode", "doctor", "gateway", "import", "install", "logs",
                      "mcp", "plugin", "plugins", "purge", "respawn", "rm", "setup-token", "stop", "kill",
                      "ultrareview", "update", "upgrade", "config", "migrate-installer"}
CLAUDE_PASSTHROUGH_FLAGS = {"-p", "--print", "-v", "--version", "-h", "--help"}
CODEX_INTERACTIVE_SUBCOMMANDS = {"resume", "fork"}
CODEX_PASSTHROUGH_FLAGS = {"-h", "--help", "-V", "--version"}
MARK_BEGIN = "# >>> agent-deck shell integration >>>"
MARK_END = "# <<< agent-deck shell integration <<<"
_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"


def native_binary(agent: str) -> str:
    env = os.environ.get(f"AGENTDECK_{agent.upper()}_BIN")
    if env and os.access(env, os.X_OK):
        return env
    from agentdeck.providers.models import agent_binary

    found = agent_binary(agent)
    if found:
        return found
    return NATIVE_DEFAULTS[agent]


def exec_native(agent: str, args: list[str]) -> "None":
    binary = native_binary(agent)
    os.execv(binary, [binary, *args])


def is_interactive_invocation(agent: str, args: list[str]) -> bool:
    if agent == "claude":
        if any(a in CLAUDE_PASSTHROUGH_FLAGS or a.startswith("--print=") for a in args):
            return False
        first = next((a for a in args if not a.startswith("-")), None)
        # a positional that is a known subcommand (and is the very first arg) is not the TUI
        return not (args and first == args[0] and first in CLAUDE_SUBCOMMANDS)
    if any(a in CODEX_PASSTHROUGH_FLAGS for a in args):
        return False
    first = next((a for a in args if not a.startswith("-")), None)
    if first is None:
        return True
    if first in CODEX_INTERACTIVE_SUBCOMMANDS:
        return True
    # codex "<prompt>" is interactive; a known non-interactive subcommand is not
    return first not in {"exec", "e", "review", "login", "logout", "mcp", "mcp-server", "plugin", "app-server",
                         "remote-control", "app", "completion", "update", "doctor", "sandbox", "debug", "apply",
                         "a", "queue", "archive", "delete", "migrate-rollouts", "unarchive", "cloud",
                         "exec-server", "features", "help", "agents"}


def resume_target(agent: str, args: list[str]) -> str | None:
    if agent == "claude":
        for i, a in enumerate(args):
            if a in ("--resume", "-r") and i + 1 < len(args) and re.fullmatch(_UUID, args[i + 1]):
                return args[i + 1]
            if a.startswith("--resume=") and re.fullmatch(_UUID, a.split("=", 1)[1]):
                return a.split("=", 1)[1]
        return None
    if args and args[0] == "resume":
        for a in args[1:]:
            if re.fullmatch(_UUID, a):
                return a
    return None


def _exec_attach(cmd: list[str]) -> "None":
    env = {k: v for k, v in os.environ.items() if k not in ("TMUX", "TMUX_PANE")}
    os.execve(cmd[0], cmd, env)


def _attach(tm, session_id: str) -> "None":
    _exec_attach(tm.attach_command(session_id))


def cmd_run(ns: argparse.Namespace) -> int:
    agent, args = ns.agent, list(ns.args)
    if args and args[0] == "--":
        args = args[1:]
    if (os.environ.get("AGENTDECK_DISABLE") or os.environ.get("AGENTDECK_SESSION_ID")
            or not (sys.stdin.isatty() and sys.stdout.isatty()) or not is_interactive_invocation(agent, args)):
        exec_native(agent, args)
    try:
        from agentdeck.terminal import TerminalManager

        tm = TerminalManager()
        target = resume_target(agent, args)
        if target:
            live = tm.find_live(agent, target)
            if live:
                print(f"agent-deck: attaching to the running managed session {live['id']}", file=sys.stderr)
                _attach(tm, live["id"])
        os.environ[f"AGENTDECK_{agent.upper()}_BIN"] = native_binary(agent)
        env = dict(os.environ)
        sess = tm.start_session(agent, os.getcwd(), extra_args=args, origin="terminal", env=env)
    except Exception as exc:  # noqa: BLE001 - never block the normal command
        print(f"agent-deck: managed session unavailable ({type(exc).__name__}: {exc}); running {agent} directly",
              file=sys.stderr)
        exec_native(agent, args)
    _attach(tm, sess["id"])
    return 0


# ---------------------------------------------------------------- attach picker

# CSI / OSC / DCS-style / two-byte escape sequences; anything left over is dropped as a control char.
_ESCAPE_RE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]?|\][^\x07\x1b]*(?:\x07|\x1b\\)?|[PX^_][^\x1b]*(?:\x1b\\)?|.?)",
                        re.S)
PICK_CANCELLED = 130


def clean_text(value: object, limit: int = 300) -> str:
    """Untrusted title/path -> one printable line without escape or control sequences."""
    text = _ESCAPE_RE.sub("", str(value if value is not None else ""))
    text = " ".join("".join(ch if ch.isprintable() else " " for ch in text).split())
    return text[:limit]


def _char_width(ch: str) -> int:
    import unicodedata

    if unicodedata.combining(ch):
        return 0
    return 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1


def fit(text: str, width: int, left: bool = False) -> str:
    """Truncate to ``width`` terminal cells with an ellipsis (from the left for paths)."""
    if width <= 0:
        return ""
    if sum(map(_char_width, text)) <= width:
        return text
    chars = reversed(text) if left else iter(text)
    kept, used = [], 1
    for ch in chars:
        w = _char_width(ch)
        if used + w > width:
            break
        kept.append(ch)
        used += w
    return "…" + "".join(reversed(kept)) if left else "".join(kept) + "…"


def _display_path(path: object) -> str:
    p = clean_text(path, 1000)
    home = str(Path.home())
    if p == home or p.startswith(home + os.sep):
        p = "~" + p[len(home):]
    return p


def session_row(s: dict, width: int, number: int | None = None) -> str:
    """One line: provider, status, title, project directory and attached marker, fitted to ``width``."""
    num = f"{number:>2}. " if number is not None else ""
    head = f"{num}{fit(clean_text(s.get('agent'), 12), 6):<6}  {fit(clean_text(s.get('status'), 20), 9):<9}  "
    tail = "  [attached]" if s.get("attached") else ""
    title = clean_text(s.get("title"), 300) or "(untitled)"
    cwd = _display_path(s.get("cwd"))
    rest = width - len(head) - len(tail)
    if rest < 8:
        return fit(head + title + tail, width)
    title_w = min(sum(map(_char_width, title)), max(rest // 2, rest - len(cwd) - 2))
    title = fit(title, title_w)
    cwd = fit(cwd, rest - sum(map(_char_width, title)) - 2, left=True)
    return head + title + ("  " + cwd if cwd else "") + tail


def parse_keys(data: bytes) -> list[str]:
    """Raw terminal bytes -> key names (several keys may arrive in one read)."""
    names = {b"[A": "up", b"OA": "up", b"[B": "down", b"OB": "down", b"[H": "home", b"OH": "home",
             b"[1~": "home", b"[F": "end", b"OF": "end", b"[4~": "end", b"[5~": "pgup", b"[6~": "pgdn"}
    single = {b"\r": "enter", b"\n": "enter", b"q": "cancel", b"Q": "cancel", b"\x03": "cancel",
              b"\x04": "cancel", b"k": "up", b"\x10": "up", b"j": "down", b"\x0e": "down"}
    keys, i = [], 0
    while i < len(data):
        b = data[i:i + 1]
        if b == b"\x1b":
            m = re.match(rb"\x1b(\[[0-?]*[ -/]*[@-~]|O[@-~])", data[i:])
            if m:
                keys.append(names.get(m.group(1), "other"))
                i += len(m.group(0))
                continue
            keys.append("cancel")  # lone Escape (or Alt+key)
            i += 2 if i + 1 < len(data) else 1
            continue
        if b.isdigit() and b != b"0":
            keys.append("digit" + b.decode())
        else:
            keys.append(single.get(b, "other"))
        i += 1
    return keys


class Menu:
    """Selection state and rendering for the interactive picker (no terminal I/O)."""

    def __init__(self, sessions: list[dict]) -> None:
        self.sessions = sessions
        unattached = [i for i, s in enumerate(sessions) if not s.get("attached")]
        self.index = unattached[0] if unattached else 0
        self.top = 0

    def key(self, name: str, page: int = 10) -> str | None:
        """Apply a key; returns "enter"/"cancel" when the menu is done."""
        n = len(self.sessions)
        if name in ("enter", "cancel"):
            return name
        if name == "up":
            self.index = (self.index - 1) % n
        elif name == "down":
            self.index = (self.index + 1) % n
        elif name == "home":
            self.index = 0
        elif name == "end":
            self.index = n - 1
        elif name == "pgup":
            self.index = max(0, self.index - page)
        elif name == "pgdn":
            self.index = min(n - 1, self.index + page)
        elif name.startswith("digit") and int(name[5:]) <= n:
            self.index = int(name[5:]) - 1
        return None

    def lines(self, cols: int, rows: int) -> list[str]:
        width = max(10, cols - 1)  # never touch the last column: avoids auto-wrap breaking redraws
        n = len(self.sessions)
        visible = max(1, min(n, rows - 3))
        if self.index < self.top:
            self.top = self.index
        elif self.index >= self.top + visible:
            self.top = self.index - visible + 1
        self.top = max(0, min(self.top, n - visible))
        out = [fit("agent-deck: choose a session to attach  (Up/Down, Enter; q or Esc cancels)", width)]
        for i in range(self.top, self.top + visible):
            row = session_row(self.sessions[i], width - 2, i + 1)
            out.append("\x1b[7m> " + row + "\x1b[0m" if i == self.index else "  " + row)
        if visible < n:
            out.append(fit(f"  ({self.top + 1}-{self.top + visible} of {n})", width))
        return out


class _Cancelled(Exception):
    pass


def _write(fd: int, text: str) -> None:
    data = text.encode(sys.stdout.encoding or "utf-8", errors="replace")
    while data:
        data = data[os.write(fd, data):]


def _terminal_size(fd: int) -> tuple[int, int]:
    try:
        size = os.get_terminal_size(fd)
        return (size.columns or 80, size.lines or 24)
    except OSError:
        return (80, 24)


def _read_keys(fd: int) -> bytes:
    """Collect split arrow sequences without treating their initial Escape as cancellation."""
    import select
    import time

    data = os.read(fd, 64)
    deadline = time.monotonic() + 0.3
    while (tail := re.search(rb"\x1b(?:\[[0-?]*[ -/]*|O)?$", data)):
        timeout = min(0.05 if tail.group() == b"\x1b" else 0.3, deadline - time.monotonic())
        if timeout <= 0 or not select.select([fd], [], [], timeout)[0]:
            break
        chunk = os.read(fd, 64)
        if not chunk:
            break
        data += chunk
    return data


def interactive_pick(sessions: list[dict], fd_in: int, fd_out: int) -> str | None:
    """Arrow-key menu on a real terminal. Terminal mode and signal handlers are always restored."""
    import select
    import signal
    import termios

    old = termios.tcgetattr(fd_in)
    new = termios.tcgetattr(fd_in)
    new[0] &= ~(termios.ICRNL | termios.IXON)
    new[3] &= ~(termios.ICANON | termios.ECHO | termios.ISIG | termios.IEXTEN)
    new[6][termios.VMIN], new[6][termios.VTIME] = 1, 0
    menu, drawn, size = Menu(sessions), 0, None

    def raise_cancel(*_a):
        raise _Cancelled

    handlers = {}
    for sig in (signal.SIGTERM, signal.SIGHUP):
        try:
            handlers[sig] = signal.signal(sig, raise_cancel)
        except (ValueError, OSError):  # not the main thread
            pass
    try:
        termios.tcsetattr(fd_in, termios.TCSANOW, new)
        _write(fd_out, "\x1b[?25l")
        while True:
            if size != (size := _terminal_size(fd_out)) or drawn == 0:
                lines = menu.lines(*size)
                up = f"\x1b[{drawn - 1}A" if drawn > 1 else ""
                _write(fd_out, up + "\r\x1b[J" + "\r\n".join(lines))
                drawn = len(lines)
            if not select.select([fd_in], [], [], 0.25)[0]:
                continue
            data = _read_keys(fd_in)
            if not data:
                return None
            for k in parse_keys(data):
                done = menu.key(k, page=max(1, size[1] - 3))
                if done:
                    return menu.sessions[menu.index]["id"] if done == "enter" else None
            size = None  # force a redraw after handled keys
    except (_Cancelled, KeyboardInterrupt):
        return None
    finally:
        try:
            if drawn:
                _write(fd_out, (f"\x1b[{drawn - 1}A" if drawn > 1 else "") + "\r\x1b[J")
            _write(fd_out, "\x1b[0m\x1b[?25h")
        finally:
            try:
                termios.tcsetattr(fd_in, termios.TCSAFLUSH, old)
            finally:
                for sig, h in handlers.items():
                    signal.signal(sig, h)


def numbered_pick(sessions: list[dict], inp, out, width: int = 80) -> str | None:
    """Line-based fallback for dumb terminals: type a number, empty line/q/EOF cancels."""
    width = max(20, width - 1)
    out.write("agent-deck: managed sessions\n")
    for i, s in enumerate(sessions, 1):
        out.write(session_row(s, width, i) + "\n")
    n = len(sessions)
    for _ in range(5):
        out.write(f"Attach to session [1-{n}] (Enter cancels): ")
        out.flush()
        line = inp.readline()
        choice = line.strip().lower()
        if not line or choice in ("", "q", "quit"):
            return None
        if choice.isdigit() and 1 <= int(choice) <= n:
            return sessions[int(choice) - 1]["id"]
        out.write(f"agent-deck: enter a number from 1 to {n}\n")
    return None


def choose_session(sessions: list[dict]) -> str | None:
    term = os.environ.get("TERM", "")
    fd_in, fd_out = sys.stdin.fileno(), sys.stdout.fileno()
    if term and term != "dumb":
        import termios

        try:
            termios.tcgetattr(fd_in)
        except termios.error:
            pass
        else:
            sys.stdout.flush()
            return interactive_pick(sessions, fd_in, fd_out)
    try:
        return numbered_pick(sessions, sys.stdin, sys.stdout, _terminal_size(fd_out)[0])
    except KeyboardInterrupt:
        print(file=sys.stdout)
        return None


def cmd_attach(ns: argparse.Namespace) -> int:
    from agentdeck.errors import AgentDeckError
    from agentdeck.terminal import TerminalManager

    picked = False
    try:
        tm = TerminalManager()
        sid = ns.session_id
        if not sid:
            live = [s for s in tm.list_sessions() if s["capabilities"]["send"]]
            if not (sys.stdin.isatty() and sys.stdout.isatty()):
                if len(live) != 1:
                    print(json.dumps([{k: s[k] for k in ("id", "agent", "title", "status", "cwd")} for s in live],
                                     indent=1))
                    print("agent-deck: pass a session id" if live else "agent-deck: no live managed sessions",
                          file=sys.stderr)
                    return 2
                sid = live[0]["id"]
            elif not live:
                print("agent-deck: no live managed sessions", file=sys.stderr)
                return 2
            else:
                sid = choose_session(live)
                if sid is None:
                    print("agent-deck: attach cancelled", file=sys.stderr)
                    return PICK_CANCELLED
                picked = True
        # attach_command re-resolves the exact managed id against the live tmux pane.
        cmd = tm.attach_command(sid)
    except (AgentDeckError, OSError) as exc:
        what = "the selected session is no longer available" if picked else \
            f"cannot attach to {clean_text(ns.session_id or 'a managed session', 40)}"
        print(f"agent-deck: {what}: {clean_text(getattr(exc, 'message', exc), 200)}", file=sys.stderr)
        return 1
    try:
        _exec_attach(cmd)
    except OSError as exc:
        print(f"agent-deck: could not start tmux ({clean_text(exc, 200)})", file=sys.stderr)
        return 1
    return 0


def cmd_list(ns: argparse.Namespace) -> int:
    from agentdeck.terminal import TerminalManager

    out = [{k: v for k, v in s.items() if not k.startswith("_")} for s in TerminalManager().list_sessions()]
    print(json.dumps(out, indent=1))
    return 0


def _post(path: str, body: dict, timeout: float = 5.0) -> tuple[int, str]:
    import urllib.error
    import urllib.request

    from agentdeck.config import local_base_url, read_local_token

    req = urllib.request.Request(local_base_url() + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {read_local_token()}"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - localhost only
            return resp.status, resp.read(2000).decode(errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(2000).decode(errors="replace")


def build_notify_body(ns: argparse.Namespace) -> dict:
    sid = ns.session or os.environ.get("AGENTDECK_SESSION_ID")
    if not sid:
        raise SystemExit("agent-deck notify: no --session and AGENTDECK_SESSION_ID is not set")
    body: dict = {"sessionId": sid, "title": ns.title[:200], "body": (ns.body or "")[:2000], "kind": ns.kind}
    if ns.total is not None:
        if ns.current is None or ns.total <= 0 or ns.current < 0:
            raise SystemExit("agent-deck notify: --current and a positive --total are both required for progress")
        body["progress"] = {"current": ns.current, "total": ns.total, "unit": ns.unit}
    if ns.stage:
        body["stage"] = ns.stage[:200]
    return body


def cmd_notify(ns: argparse.Namespace) -> int:
    body = build_notify_body(ns)
    try:
        code, text = _post("/api/v1/notify", body)
    except OSError as exc:
        print(f"agent-deck notify: helper not reachable ({exc})", file=sys.stderr)
        return 1
    if code >= 300:
        print(f"agent-deck notify: HTTP {code} {text[:200]}", file=sys.stderr)
        return 1
    return 0


def hook_to_notify(agent: str, payload: dict) -> dict | None:
    """Map a native hook payload to a notify body, or None to ignore."""
    sid = os.environ.get("AGENTDECK_SESSION_ID") or payload.get("session_id") or payload.get("thread-id") \
        or payload.get("thread_id")
    if not isinstance(sid, str) or not sid:
        return None
    if agent == "claude":
        ev = payload.get("hook_event_name")
        if ev == "Stop":
            return {"sessionId": sid, "kind": "completed", "title": "Claude finished", "body": ""}
        if ev == "Notification":
            msg = str(payload.get("message") or "Claude needs your input")
            return {"sessionId": sid, "kind": "input", "title": "Claude needs input", "body": msg[:500]}
        if ev == "StopFailure":
            return {"sessionId": sid, "kind": "error", "title": "Claude stopped with an error",
                    "body": str(payload.get("error") or "")[:500]}
        return None
    t = payload.get("type")
    if t == "agent-turn-complete":
        last = payload.get("last-assistant-message") or ""
        return {"sessionId": sid, "kind": "completed", "title": "Codex finished", "body": str(last)[:500]}
    return None


def cmd_hook(ns: argparse.Namespace) -> int:
    try:
        raw = ns.payload if ns.payload else sys.stdin.read(1_000_000)
        payload = json.loads(raw) if raw.strip() else {}
        body = hook_to_notify(ns.agent, payload if isinstance(payload, dict) else {})
        if body:
            _post("/api/v1/notify", body, timeout=3.0)
    except Exception:  # noqa: BLE001 - hooks must never break the agent
        pass
    return 0


def cmd_pair(ns: argparse.Namespace) -> int:
    from agentdeck import admin

    return int(admin.main(["pair", *ns.args]) or 0)


# ---------------------------------------------------------------- shell integration

def shell_block(python: str, src: str, claude_bin: str, codex_bin: str) -> str:
    q = shlex.quote
    fn = []
    for agent, binary in (("claude", claude_bin), ("codex", codex_bin)):
        fn.append(f"""  {agent}() {{
    if [[ -n "$AGENTDECK_DISABLE" || -n "$AGENTDECK_SESSION_ID" || ! -t 0 || ! -t 1 || ! -x "$_agentdeck_py" ]]; then
      {q(binary)} "$@"; return
    fi
    AGENTDECK_{agent.upper()}_BIN={q(binary)} agentdeck run {agent} "$@"
  }}""")
    return "\n".join([
        MARK_BEGIN,
        "# Generated by `agentdeck install-shell`. Remove this block (or rerun with --uninstall) to undo.",
        "# `claude`/`codex` open in a managed tmux pane that the Agent Deck phone app can see and control.",
        "# Bypass once: AGENTDECK_DISABLE=1 claude ...   or   command claude ...   (native binaries unchanged)",
        "if [[ -o interactive ]]; then",
        f"  _agentdeck_py={q(python)}",
        f"  _agentdeck_src={q(src)}",
        '  agentdeck() { PYTHONPATH="$_agentdeck_src" "$_agentdeck_py" -m agentdeck.cli "$@"; }',
        '  ad() { if (( $# )); then agentdeck "$@"; else agentdeck attach; fi; }',
        *fn,
        "fi",
        MARK_END,
    ]) + "\n"


def apply_block(text: str, block: str | None) -> str:
    """Replace (or remove when block is None) the marked block; append if absent. Idempotent."""
    pattern = re.compile(re.escape(MARK_BEGIN) + r".*?" + re.escape(MARK_END) + r"\n?", re.S)
    if pattern.search(text):
        return pattern.sub(lambda _m: block or "", text, count=1)
    if block is None:
        return text
    sep = "" if not text or text.endswith("\n") else "\n"
    return text + sep + ("\n" if text else "") + block


def cmd_install_shell(ns: argparse.Namespace) -> int:
    src = str(Path(__file__).resolve().parent.parent)
    python = ns.python or str(Path(src) / ".venv" / "bin" / "python")
    block = None if ns.uninstall else shell_block(python, src, ns.claude_bin or native_binary("claude"),
                                                  ns.codex_bin or native_binary("codex"))
    target = Path(os.path.expanduser(ns.target))
    try:
        current = target.read_text(encoding="utf-8")
    except FileNotFoundError:
        current = ""
    new = apply_block(current, block)
    if ns.dry_run:
        action = "unchanged" if new == current else ("remove block from" if ns.uninstall else "write block to")
        print(f"# dry run: would {action} {target}")
        if block:
            print(block, end="")
        return 0
    if new != current:
        tmp = target.with_name(target.name + ".agentdeck-tmp")
        mode = target.stat().st_mode & 0o777 if target.exists() else 0o644
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(new)
        os.replace(tmp, target)
        print(f"agent-deck: updated {target}")
    else:
        print(f"agent-deck: {target} already up to date")
    return 0


# ---------------------------------------------------------------- debug

def cmd_sessions(ns: argparse.Namespace) -> int:
    from agentdeck import providers

    keep = ("id", "agent", "nativeId", "status", "statusEvidence", "model", "live", "isSubagent",
            "parentSessionId", "agentName", "agentRole", "projectRoot", "updatedAt")
    rows = [{k: s.get(k) for k in keep} for s in providers.discover_sessions()]
    print(json.dumps(rows, indent=1))
    return 0


def cmd_models(ns: argparse.Namespace) -> int:
    from agentdeck import providers

    print(json.dumps(providers.discover_models(refresh=ns.refresh), indent=1))
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["pair"]:
        # argparse REMAINDER drops leading options (--json, --svg), so forward everything verbatim.
        return cmd_pair(argparse.Namespace(args=argv[1:]))
    p = argparse.ArgumentParser(prog="agentdeck", description="Agent Deck local CLI")
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="run claude/codex in a managed tmux pane and attach")
    r.add_argument("agent", choices=["claude", "codex"])
    r.add_argument("args", nargs=argparse.REMAINDER)
    r.set_defaults(fn=cmd_run)
    a = sub.add_parser("attach", help="attach this terminal to a managed session")
    a.add_argument("session_id", nargs="?")
    a.set_defaults(fn=cmd_attach)
    sub.add_parser("list", help="list managed sessions").set_defaults(fn=cmd_list)
    n = sub.add_parser("notify", help="send a phone notification through the local helper")
    n.add_argument("--session", help="companion or native session id (default $AGENTDECK_SESSION_ID)")
    n.add_argument("--kind", choices=["progress", "completed", "error", "input"], default="progress")
    n.add_argument("--title", required=True)
    n.add_argument("--body", default="")
    n.add_argument("--current", type=float)
    n.add_argument("--total", type=float)
    n.add_argument("--unit")
    n.add_argument("--stage")
    n.set_defaults(fn=cmd_notify)
    h = sub.add_parser("hook", help="adapter for Claude Code hooks (stdin JSON) / Codex notify (argv JSON)")
    h.add_argument("agent", choices=["claude", "codex"])
    h.add_argument("payload", nargs="?")
    h.set_defaults(fn=cmd_hook)
    pr = sub.add_parser("pair", help="create a pairing QR code (agentdeck.admin pair)")
    pr.add_argument("args", nargs=argparse.REMAINDER)
    pr.set_defaults(fn=cmd_pair)
    i = sub.add_parser("install-shell", help="write the idempotent zsh integration block into TARGET")
    i.add_argument("target", help="file to update, e.g. ~/.zshrc (nothing else is touched)")
    i.add_argument("--dry-run", action="store_true", help="print the block and the planned action only")
    i.add_argument("--uninstall", action="store_true", help="remove the block")
    i.add_argument("--python", help="python executable of the Agent Deck venv")
    i.add_argument("--claude-bin", help="absolute native claude binary")
    i.add_argument("--codex-bin", help="absolute native codex binary")
    i.set_defaults(fn=cmd_install_shell)
    sub.add_parser("sessions", help="discovered sessions (metadata only)").set_defaults(fn=cmd_sessions)
    m = sub.add_parser("models", help="live model discovery")
    m.add_argument("--refresh", action="store_true")
    m.set_defaults(fn=cmd_models)
    ns = p.parse_args(argv)
    return int(ns.fn(ns) or 0)


if __name__ == "__main__":
    sys.exit(main())
