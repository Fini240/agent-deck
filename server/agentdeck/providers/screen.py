"""Parse what a native TUI currently shows (tmux ``capture-pane`` text).

Only *observed* terminal state is reported:
- ``dialog``: a selection prompt (tool permission, trust folder, MCP consent, ...)
  identified by a numbered/cursor option list directly above a confirm/cancel footer.
- ``working``: Claude's spinner line ("✻ Ideating… (4s · thinking)") or Codex's
  "Working (… esc to interrupt)" line.
- ``input``: the editable prompt box and its current draft text.

Patterns are based on real captures from Claude Code 2.1.290 and Codex 0.160.1
(see tests/providers/fixtures/screens). Unknown layouts yield ``status='unknown'``
instead of guesses.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field

ANSI_RE = re.compile(r"\x1b\[[0-9;:?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[@-Z\\-_]")
SEPARATOR_RE = re.compile(r"^\s*[─━╌┄-]{12,}\s*$")
FOOTER_RE = re.compile(
    r"(?i)(enter to (confirm|select|continue)|esc to (cancel|go back|exit)|press enter to confirm|"
    r"↑/↓ to (navigate|select)|\(esc\)\s*$|to navigate · enter)"
)
NUMBERED_RE = re.compile(r"^(?P<indent>\s*)(?P<cur>[❯›>▶]\s*)?(?P<num>\d{1,2})[.)]\s+(?P<label>\S.*?)\s*$")
CURSOR_RE = re.compile(r"^(?P<indent>\s*)(?P<cur>[❯›>▶])\s+(?P<label>\S.*?)\s*$")
CLAUDE_SPINNER_RE = re.compile(r"^\s*\S\s+(?P<verb>[A-Z][A-Za-z'\-]{2,30})…(?:\s*\((?P<info>[^)]*)\))?")
CODEX_WORKING_RE = re.compile(r"^\s*\S?\s*(?P<verb>Working|Thinking|Running|Compacting[a-z ]*)\b.*?\((?P<info>[^)]*)\)")
ESC_INTERRUPT_RE = re.compile(r"(?i)\besc to interrupt\b")
CLAUDE_PROMPT_RE = re.compile(r"^❯[  ](?P<text>.*)$|^❯$")
CODEX_PROMPT_RE = re.compile(r"^›[  ](?P<text>.*)$|^›$")
# fresh-start suggestion and the hint shown while a message is queued during a running turn
CLAUDE_PLACEHOLDER_RE = re.compile(r'^(Try ".*"|Press up to edit queued messages)$')
PROMPT_LINE_RE = re.compile(r"^\s*[❯›][\u00a0 ]\S")
DASHED_RE = re.compile(r"^\s*[╌┄]{8,}\s*$")
CODEX_FOOTER_RE = re.compile(r"(?i)(context \d+% left|\? for shortcuts|tab to queue|for agents)")


@dataclass
class Option:
    index: int  # 0-based position in the list
    label: str
    number: int | None  # explicit number shown in the TUI, if any


@dataclass
class Dialog:
    title: str
    detail: str
    options: list[Option]
    cursor: int | None
    fingerprint: str

    @property
    def approval_id(self) -> str:
        return "ap_" + self.fingerprint[:16]

    def choice_id(self, opt: Option) -> str:
        return str(opt.number if opt.number is not None else opt.index + 1)


@dataclass
class ScreenState:
    agent: str
    working: bool = False
    activity: str | None = None
    dialog: Dialog | None = None
    input_visible: bool = False
    draft: str | None = None  # None = unknown / no prompt box
    notes: list[str] = field(default_factory=list)

    @property
    def status(self) -> str:
        if self.dialog is not None:
            return "needs_input"
        if self.working:
            return "working"
        if self.input_visible:
            return "idle"
        return "unknown"

    @property
    def ready(self) -> bool:
        return self.dialog is None and not self.working and self.input_visible


def strip_ansi(text: str) -> str:
    return ANSI_RE.sub("", text)


def _lines(text: str) -> list[str]:
    lines = [ln.rstrip() for ln in strip_ansi(text).replace("\r", "").split("\n")]
    while lines and not lines[-1].strip():
        lines.pop()
    return lines


def _fingerprint(agent: str, title: str, detail: str, labels: list[str]) -> str:
    norm = "\x1f".join([agent, " ".join(title.split()), " ".join(detail.split()), *(" ".join(x.split()) for x in labels)])
    return hashlib.sha256(norm.encode("utf-8")).hexdigest()


def find_dialog(agent: str, lines: list[str]) -> Dialog | None:
    window_start = max(0, len(lines) - 45)
    footer_idx = None
    for i in range(len(lines) - 1, window_start - 1, -1):
        if FOOTER_RE.search(lines[i]):
            footer_idx = i
            break
    if footer_idx is None:
        return None
    # walk upward collecting the option block (allowing wrapped continuation lines)
    j = footer_idx - 1
    while j >= window_start and not lines[j].strip():
        j -= 1
    block_end = j
    raw: list[tuple[int, str]] = []  # (line index, text)
    numbered = False
    cont: list[str] = []
    k = block_end
    while k >= window_start and k >= block_end - 30:
        ln = lines[k]
        m = NUMBERED_RE.match(ln)
        if m:
            numbered = True
            label = " ".join([m.group("label")] + list(reversed(cont)))
            raw.append((k, label))
            cont = []
            k -= 1
            continue
        if numbered:
            if ln.strip() and not SEPARATOR_RE.match(ln) and not CURSOR_RE.match(ln) and ln.startswith("    "):
                # continuation lines belong to the option *above* them; buffer them
                cont.append(ln.strip())
                k -= 1
                continue
            break
        if ln.strip() and ln.startswith("     ") and not raw:
            cont.append(ln.strip())
            k -= 1
            continue
        break
    options: list[Option] = []
    cursor: int | None = None
    start_idx = None
    if numbered and raw:
        raw.reverse()
        start_idx = raw[0][0]
        for pos, (li, label) in enumerate(raw):
            m = NUMBERED_RE.match(lines[li])
            if m and m.group("cur"):
                cursor = pos
            options.append(Option(pos, label, int(m.group("num")) if m else None))
    else:
        # unnumbered list: one cursor line and siblings aligned with its label column
        cur_line = None
        for i in range(block_end, max(window_start, block_end - 15) - 1, -1):
            if CURSOR_RE.match(lines[i]):
                cur_line = i
                break
        if cur_line is None:
            return None
        m = CURSOR_RE.match(lines[cur_line])
        col = m.start("label")
        i = cur_line
        while i - 1 >= window_start and lines[i - 1].strip() and _aligned(lines[i - 1], col):
            i -= 1
        start_idx = i
        end = cur_line
        while end + 1 <= block_end and lines[end + 1].strip() and _aligned(lines[end + 1], col):
            end += 1
        for pos, li in enumerate(range(start_idx, end + 1)):
            text = lines[li]
            cm = CURSOR_RE.match(text)
            if cm and li == cur_line:
                cursor = pos
                label = cm.group("label")
            else:
                label = text.strip()
            options.append(Option(pos, label, None))
        if len(options) < 2:
            return None
    if not options:
        return None
    # title: the closest question line ("Do you want…?", "Would you like…?") a few lines above the
    # options, else the nearest non-empty line. detail: text between title and options plus up to
    # 14 lines above the title, stopping at the user's prompt line.
    t = start_idx - 1
    while t >= window_start and not lines[t].strip():
        t -= 1
    title_idx = t
    title_cut: int | None = None
    between: list[str] = []
    q, seen = t, 0
    while q >= window_start and seen < 8 and not SEPARATOR_RE.match(lines[q]):
        s = lines[q].strip()
        if s:
            if s.endswith("?"):
                title_idx = q
                break
            if "? " in s:  # wrapped question, e.g. Claude's folder-trust dialog
                title_idx = q
                title_cut = s.index("? ") + 1
                break
            between.append(s)
            seen += 1
        q -= 1
    else:
        between = []
    title = "" if title_idx < window_start or SEPARATOR_RE.match(lines[title_idx]) else lines[title_idx].strip()
    detail_lines: list[str] = list(between)
    if title_cut is not None:
        if title[title_cut:].strip():
            detail_lines.append(title[title_cut:].strip())
        title = title[:title_cut]
    d = title_idx - 1
    while d >= window_start and len(detail_lines) < 14:
        if PROMPT_LINE_RE.match(lines[d]):
            break
        if not SEPARATOR_RE.match(lines[d]) and not DASHED_RE.match(lines[d]):
            detail_lines.append(lines[d].strip())
        d -= 1
    detail = "\n".join(x for x in reversed(detail_lines) if x)
    fp = _fingerprint(agent, title, detail, [o.label for o in options])
    return Dialog(title=title or "Choose an option", detail=detail, options=options, cursor=cursor, fingerprint=fp)


def _aligned(line: str, col: int) -> bool:
    if len(line) <= col:
        return False
    stripped = len(line) - len(line.lstrip())
    if stripped == col:
        return True
    m = CURSOR_RE.match(line)
    return bool(m and m.start("label") == col)


def _claude_input(lines: list[str]) -> tuple[bool, str | None, int | None]:
    """(visible, draft, top_index). Prompt box = separator, '❯ …' lines, separator."""
    for i in range(len(lines) - 1, max(-1, len(lines) - 40), -1):
        m = CLAUDE_PROMPT_RE.match(lines[i])
        if not m:
            continue
        if i == 0 or not SEPARATOR_RE.match(lines[i - 1]):
            continue
        parts = [m.group("text") or ""]
        j = i + 1
        while j < len(lines) and not SEPARATOR_RE.match(lines[j]):
            parts.append(lines[j][2:] if lines[j].startswith("  ") else lines[j])
            j += 1
        draft = "\n".join(parts).strip()
        if CLAUDE_PLACEHOLDER_RE.match(draft):
            draft = ""
        return True, draft, i - 1
    return False, None, None


def _codex_input(lines: list[str], styled: list[str] | None) -> tuple[bool, str | None, int | None]:
    for i in range(len(lines) - 1, max(-1, len(lines) - 25), -1):
        m = CODEX_PROMPT_RE.match(lines[i])
        if not m:
            continue
        parts = [m.group("text") or ""]
        j = i + 1
        while j < len(lines) and lines[j].strip() and not CODEX_FOOTER_RE.search(lines[j]) and lines[j].startswith("  "):
            parts.append(lines[j][2:])
            j += 1
        draft = "\n".join(parts).strip()
        if styled is not None and i < len(styled) and draft and _codex_placeholder(styled[i]):
            draft = ""
        elif styled is None and draft in ("Ask Codex to do anything",):
            draft = ""
        return True, draft, i
    return False, None, None


def _codex_placeholder(styled_line: str) -> bool:
    """Codex renders the empty-composer placeholder dim (SGR 2)."""
    rest = re.sub(r"^(\x1b\[[0-9;]*m)*›(\x1b\[[0-9;]*m)*[  ]", "", styled_line)
    return bool(re.fullmatch(r"(\x1b\[[0-9;]*m)*\x1b\[2m[^\x1b]*(\x1b\[[0-9;]*m)*\s*", rest))


def parse_screen(agent: str, text: str, styled: str | None = None) -> ScreenState:
    lines = _lines(text)
    styled_lines = None
    if styled is not None:
        styled_lines = styled.replace("\r", "").split("\n")
    st = ScreenState(agent=agent)
    st.dialog = find_dialog(agent, lines)
    if agent == "claude":
        visible, draft, top = _claude_input(lines)
    else:
        visible, draft, top = _codex_input(lines, styled_lines)
    if st.dialog is not None:
        visible, draft = False, None
    st.input_visible, st.draft = visible, draft
    # activity: only consider lines just above the prompt box (or the bottom of the screen)
    end = top if top is not None else len(lines)
    region = lines[max(0, end - 14):end]
    for ln in reversed(region):
        if agent == "claude":
            m = CLAUDE_SPINNER_RE.match(ln)
            if m:
                st.working = True
                info = (m.group("info") or "").strip()
                st.activity = f"{m.group('verb')}… ({info})" if info else f"{m.group('verb')}…"
                break
        else:
            m = CODEX_WORKING_RE.match(ln)
            if m:
                st.working = True
                st.activity = ln.strip()[:120]
                break
        if ESC_INTERRUPT_RE.search(ln):
            st.working = True
            st.activity = ln.strip()[:120]
            break
    return st
