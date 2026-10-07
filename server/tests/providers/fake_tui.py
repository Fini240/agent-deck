"""Fake Claude/Codex TUI for real-tmux tests.

Mimics the observed behaviour of Claude Code 2.1.290 / Codex 0.160.1:
- enables bracketed paste and treats pasted text as draft text (newlines included)
- Enter submits the draft; a draft starting with "work" starts a turn (spinner) that only
  ends on Escape or after "work <seconds>"; "ask" opens a numbered permission dialog
- Escape interrupts a running turn and RESTORES the interrupted prompt into the draft (Claude)
- Ctrl-U kills one draft line, Backspace deletes a char (joins lines)
- Ctrl-C is logged (tests assert it is never sent)

Every input event is appended as JSON to the log file named by $FAKE_TUI_LOG.
Style: $FAKE_TUI_STYLE = claude | codex.
"""

from __future__ import annotations

import json
import os
import select
import sys
import termios
import time
import tty

STYLE = os.environ.get("FAKE_TUI_STYLE", "claude")
LOG = os.environ.get("FAKE_TUI_LOG", "/dev/null")
NBSP = " "
SEP = "─" * 70


def log(event: str, **kw) -> None:
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"event": event, "t": time.time(), **kw}) + "\n")


class App:
    def __init__(self) -> None:
        self.draft = ""
        self.history: list[str] = []
        self.working_since: float | None = None
        self.work_until: float | None = None
        self.current_prompt = ""
        self.dialog: dict | None = None
        self.in_paste = False
        self.paste_buf = ""
        self.ctrl_c = 0

    # ------------------------------------------------------------ rendering
    def render(self) -> None:
        out = ["\x1b[H\x1b[2J"]
        lines: list[str] = [f"Fake {STYLE} TUI", ""]
        lines += self.history[-12:]
        lines.append("")
        if self.dialog:
            lines.append(SEP)
            lines.append(" Bash command")
            lines.append(f"   {self.dialog['command']}")
            lines.append("")
            lines.append(" Do you want to proceed?" if STYLE == "claude" else "  Would you like to run the following command?")
            for i, opt in enumerate(self.dialog["options"]):
                cur = "❯" if STYLE == "claude" else "›"
                prefix = f" {cur} " if i == self.dialog["cursor"] else "   "
                lines.append(f"{prefix}{i + 1}. {opt}")
            lines.append("")
            lines.append(" Esc to cancel · Tab to amend" if STYLE == "claude" else "  Press enter to confirm or esc to cancel")
        else:
            if self.working_since is not None:
                secs = int(time.time() - self.working_since)
                if STYLE == "claude":
                    lines.append(f"✻ Ideating… ({secs}s · thinking)")
                else:
                    lines.append(f"• Working ({secs}s • esc to interrupt)")
                lines.append("")
            if STYLE == "claude":
                lines.append(SEP)
                dl = self.draft.split("\n") if self.draft else [""]
                lines.append(f"❯{NBSP}{dl[0]}")
                lines += [f"  {x}" for x in dl[1:]]
                lines.append(SEP)
                lines.append("  Fake 1.0 | ctx: 100% remaining")
                lines.append("  ⏵⏵ bypass permissions on · ← for agents")
            else:
                dl = self.draft.split("\n") if self.draft else [""]
                if self.draft:
                    lines.append(f"\x1b[1m›\x1b[0m {dl[0]}")
                else:
                    lines.append("\x1b[1m›\x1b[0m \x1b[2mAsk Codex to do anything\x1b[0m")
                lines += [f"  {x}" for x in dl[1:]]
                lines.append("")
                lines.append("  Context 100% left · Fake-Model high")
                lines.append("  ← for agents · ? for shortcuts")
        out.append("\r\n".join(lines))
        sys.stdout.write("".join(out))
        sys.stdout.flush()

    # ------------------------------------------------------------ actions
    def submit(self) -> None:
        text = self.draft
        if not text.strip():
            return
        log("submit", text=text)
        self.history.append(("> " if STYLE == "codex" else "❯ ") + text.split("\n")[0])
        self.draft = ""
        self.current_prompt = text
        if text.startswith("work"):
            parts = text.split()
            self.working_since = time.time()
            self.work_until = time.time() + float(parts[1]) if len(parts) > 1 and parts[1].replace(".", "").isdigit() else None
        elif text.startswith("ask exit"):
            self.dialog = {"command": "trust check " + str(len(self.history)),
                           "options": ["No, exit", "Yes, I trust this folder"], "cursor": 0}
        elif text.startswith("ask"):
            self.dialog = {"command": "rm -rf /tmp/fake-" + str(len(self.history)),
                           "options": ["Yes", "Yes, and don't ask again", "No"], "cursor": 0}
        else:
            self.history.append(f"⏺ echo: {text}")

    def escape(self) -> None:
        if self.dialog:
            log("dialog_cancel")
            self.dialog = None
            self.history.append("⎿ cancelled")
        elif self.working_since is not None:
            log("interrupt")
            self.working_since = None
            self.work_until = None
            self.history.append("⎿ Interrupted · What should Claude do instead?")
            if STYLE == "claude":
                self.draft = self.current_prompt  # Claude restores the interrupted prompt
        else:
            log("escape_idle")

    def choose(self, idx: int) -> None:
        if not self.dialog or not (0 <= idx < len(self.dialog["options"])):
            return
        log("choice", index=idx, label=self.dialog["options"][idx], command=self.dialog["command"])
        self.history.append(f"⎿ chose {self.dialog['options'][idx]}")
        if self.dialog["options"][idx] == "No, exit":
            log("exit")
            os._exit(0)  # like Claude's folder-trust dialog: this choice quits the TUI
        self.dialog = None

    def key(self, data: str) -> None:
        if self.in_paste:
            end = data.find("\x1b[201~")
            if end < 0:
                self.paste_buf += data
                return
            self.paste_buf += data[:end]
            self.in_paste = False
            log("paste", text=self.paste_buf)
            if not self.dialog:
                self.draft += self.paste_buf
            self.paste_buf = ""
            rest = data[end + 6:]
            if rest:
                self.key(rest)
            return
        i = 0
        while i < len(data):
            if data.startswith("\x1b[200~", i):
                self.in_paste = True
                self.key(data[i + 6:])
                return
            ch = data[i]
            if data.startswith("\x1b[A", i) or data.startswith("\x1bOA", i):
                if self.dialog:
                    self.dialog["cursor"] = max(0, self.dialog["cursor"] - 1)
                log("up")
                i += 3
                continue
            if data.startswith("\x1b[B", i) or data.startswith("\x1bOB", i):
                if self.dialog:
                    self.dialog["cursor"] = min(len(self.dialog["options"]) - 1, self.dialog["cursor"] + 1)
                log("down")
                i += 3
                continue
            if ch == "\x1b":
                self.escape()
            elif ch in "\r\n":
                if self.dialog:
                    self.choose(self.dialog["cursor"])
                else:
                    self.submit()
            elif ch == "\x03":
                log("ctrl_c")
                self.ctrl_c += 1
                if self.ctrl_c >= 2 and self.working_since is None:
                    raise SystemExit(0)
            elif ch == "\x15":
                log("ctrl_u")
                if "\n" in self.draft:
                    self.draft = self.draft[: self.draft.rfind("\n") + 1]
                else:
                    self.draft = ""
            elif ch in ("\x7f", "\x08"):
                log("backspace")
                self.draft = self.draft[:-1]
            elif ch == "\t":
                log("tab")
            elif self.dialog and ch.isdigit():
                self.choose(int(ch) - 1)
            elif ch >= " ":
                log("typed", ch=ch)
                if not self.dialog:
                    self.draft += ch
            i += 1


def main() -> None:
    log("start", argv=sys.argv[1:], cwd=os.getcwd(), session=os.environ.get("AGENTDECK_SESSION_ID"))
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    tty.setraw(fd)
    sys.stdout.write("\x1b[?2004h")
    app = App()
    if "--" in sys.argv:
        app.draft = sys.argv[sys.argv.index("--") + 1]
        app.submit()
    try:
        last_render = 0.0
        while True:
            r, _, _ = select.select([fd], [], [], 0.2)
            if r:
                data = os.read(fd, 65536).decode("utf-8", "replace")
                if not data:
                    break
                app.key(data)
            if app.work_until and time.time() >= app.work_until:
                app.working_since = None
                app.work_until = None
                app.history.append("⏺ done working")
                log("work_done")
            if r or time.time() - last_render > 0.5:
                app.render()
                last_render = time.time()
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)
        sys.stdout.write("\x1b[?2004l")


if __name__ == "__main__":
    main()
