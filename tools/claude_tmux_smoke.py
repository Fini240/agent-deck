"""Minimal live smoke: real Claude Code (haiku) in a managed tmux pane, interrupt + same-session follow-up.

Uses a throwaway terminal state dir (/tmp/adsmoke) and kills only that private tmux server at the end.
Usage: cd server && .venv/bin/python ../tools/claude_tmux_smoke.py
"""
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))
from agentdeck import providers  # noqa: E402
from agentdeck.providers import claude as cp  # noqa: E402
from agentdeck.terminal import TerminalManager, login_environment  # noqa: E402

state = Path("/tmp/adsmoke/st")
shutil.rmtree(state.parent, ignore_errors=True)
tm = TerminalManager(state)


def wait(pred, timeout, what):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        s = tm.get_session(sid)
        if pred(s):
            return s
        time.sleep(0.3)
    print("TIMEOUT waiting for", what, "last:", s["status"], s["stage"])
    print(tm.preview(sid)[-1500:])
    raise SystemExit(1)


try:
    s = tm.start_session("claude", str(Path.home()), model="haiku", env=login_environment())
    sid, native = s["id"], s["nativeId"]
    print("started", sid, "nativeId", native)
    s = wait(lambda x: x["status"] in ("idle", "needs_input"), 60, "ready")
    if s["status"] == "needs_input":
        print("startup dialog:", tm.approvals(sid)[0]["title"]); raise SystemExit(1)
    print("ready: status", s["status"])
    tm.send(sid, "Count from 1 to 60, one number per line, each with a one-sentence fun fact. Take your time.")
    s = wait(lambda x: x["status"] == "working", 20, "working")
    print("working: stage", repr(s["stage"]))
    delay = float(sys.argv[1]) if len(sys.argv) > 1 else 2.0
    s = wait(lambda x: x["status"] == "working" and "hook" not in (x["stage"] or ""), 30, "generation") if delay > 2 else s
    time.sleep(delay)
    print("before interrupt: stage", repr(tm.get_session(sid)["stage"]))
    t = time.monotonic()
    tm.send(sid, "Stop counting. Reply with exactly: AGENTDECK-OK", interrupt=True)
    print(f"follow-up accepted after interrupt in {time.monotonic() - t:.1f}s")
    s = wait(lambda x: x["status"] == "idle" and x["lastMessage"] and "AGENTDECK-OK" in x["lastMessage"], 60, "reply")
    msgs = providers.read_messages(s, 30)
    users = [m["text"] for m in msgs if m["role"] == "user"]
    print("same nativeId:", s["nativeId"] == native, "| transcript user turns:", len(users),
          "| last user turn is follow-up:", users[-1].startswith("Stop counting"),
          "| last assistant:", [m["text"] for m in msgs if m["role"] == "assistant"][-1][:40])
    pane_pid = tm._live_panes()[sid]["pid"]
    reg = cp.session_for_pid(pane_pid)
    print("claude registry for pane pid -> sessionId matches:", bool(reg) and reg.get("sessionId") == native,
          "| registry status:", (reg or {}).get("status"))
except Exception as exc:
    print("ERROR", type(exc).__name__, exc)
    rec, info = tm._resolve(sid)
    o = tm._observe(rec, info["paneId"])
    print("observe:", o.status, repr(o.draft), o.working, o.activity)
    print(tm._capture(info["paneId"])[-3000:])
    raise SystemExit(1)
finally:
    subprocess.run([tm.tmux, "-S", str(tm.socket), "kill-server"], capture_output=True)
