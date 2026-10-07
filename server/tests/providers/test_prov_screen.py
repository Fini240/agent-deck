from __future__ import annotations

from pathlib import Path

from agentdeck.providers.screen import parse_screen

FIX = Path(__file__).parent / "fixtures" / "screens"


def load(name: str) -> str:
    return (FIX / name).read_text(encoding="utf-8")


def test_actual_codex_0160_idle_and_working_captures():
    idle = parse_screen("codex", load("codex_live_idle.txt"), load("codex_live_idle_styled.txt"))
    assert idle.ready and idle.draft == "" and idle.status == "idle"
    working = parse_screen("codex", load("codex_live_working.txt"))
    assert working.status == "working" and not working.ready


def test_claude_fresh_idle_placeholder_is_not_a_draft():
    st = parse_screen("claude", load("claude_idle_fresh.txt"))
    assert st.status == "idle" and st.ready
    assert st.draft == ""
    assert st.dialog is None


def test_claude_spinner_means_working_even_with_prompt_box_visible():
    st = parse_screen("claude", load("claude_working.txt"))
    assert st.working and st.status == "working"
    assert st.activity == "Ideating… (4s · thinking)"
    assert not st.ready


def test_claude_finished_turn_is_idle():
    st = parse_screen("claude", load("claude_done_idle.txt"))
    assert not st.working and st.status == "idle" and st.ready
    assert st.draft == ""


def test_claude_restored_prompt_after_interrupt_is_a_draft():
    st = parse_screen("claude", load("claude_restored_draft.txt"))
    assert st.status == "idle"
    assert st.draft == "Count slowly from 1 to 200, one number per line, explaining each."


def test_claude_multiline_draft():
    st = parse_screen("claude", load("claude_multiline_draft.txt"))
    assert st.draft == "line one\nline two\nline three"


def test_claude_permission_dialog_options_and_fingerprint():
    st = parse_screen("claude", load("claude_permission.txt"))
    assert st.status == "needs_input" and not st.ready
    d = st.dialog
    assert d is not None
    assert d.title == "Do you want to create a.txt?"
    assert [o.number for o in d.options] == [1, 2, 3]
    assert d.options[0].label == "Yes"
    assert d.options[1].label.startswith("Yes, and switch to accept edits")
    assert "always allow access" in d.options[1].label  # wrapped continuation joined
    assert d.options[2].label == "No"
    assert d.cursor == 0
    assert "a.txt" in d.detail
    # moving the cursor must not change identity; changing the prompt must
    moved = load("claude_permission.txt").replace(" ❯ 1. Yes", "   1. Yes").replace("   3. No", " ❯ 3. No")
    d2 = parse_screen("claude", moved).dialog
    assert d2 is not None and d2.fingerprint == d.fingerprint and d2.cursor == 2
    other = load("claude_permission.txt").replace("a.txt", "b.txt")
    assert parse_screen("claude", other).dialog.fingerprint != d.fingerprint


def test_claude_unnumbered_trust_dialog():
    d = parse_screen("claude", load("claude_trust.txt")).dialog
    assert d is not None
    assert [o.label for o in d.options] == ["No, exit", "Yes, I trust this folder"]
    assert d.title == "Quick safety check: Is this a project you created or one you trust?"
    assert "(Like your own code" in d.detail and "Security guide" in d.detail
    assert [o.number for o in d.options] == [None, None]
    assert d.cursor == 0
    assert d.choice_id(d.options[1]) == "2"


def test_claude_mcp_dialog_cursor_on_last():
    d = parse_screen("claude", load("claude_mcp.txt")).dialog
    assert d is not None
    assert len(d.options) == 3
    assert d.cursor == 2
    assert d.options[2].label == "Continue without using this MCP server"


def test_codex_idle_placeholder_plain_and_styled():
    st = parse_screen("codex", load("codex_idle.txt"))
    assert st.status == "idle" and st.draft == ""
    styled = load("codex_idle_prompt_styled.txt")
    st2 = parse_screen("codex", "› Ask Codex to do anything\n\n  Context 100% left", styled)
    assert st2.draft == ""
    styled_draft = load("codex_draft_prompt_styled.txt")
    st3 = parse_screen("codex", "› hello draft\n\n  Context 100% left", styled_draft)
    assert st3.draft == "hello draft"


def test_codex_working_and_approval():
    st = parse_screen("codex", load("codex_working_from_binary_strings.txt"))
    assert st.working and st.status == "working"
    ap = parse_screen("codex", load("codex_approval_from_binary_strings.txt"))
    assert ap.status == "needs_input"
    d = ap.dialog
    assert d.title == "Would you like to run the following command?"
    assert [o.label for o in d.options][0] == "Yes, proceed (y)"
    assert d.cursor == 0
    assert "rm -rf /tmp/agentdeck-test-output" in d.detail


def test_assistant_numbered_list_is_not_a_dialog():
    text = load("claude_done_idle.txt").replace(
        "⏺ The command is running", "⏺ Options:\n  1. Yes\n  2. No\n⏺ The command is running")
    st = parse_screen("claude", text)
    assert st.dialog is None and st.status == "idle"


def test_claude_streaming_screen_has_no_spinner_and_queued_hint_is_not_a_draft():
    st = parse_screen("claude", load("claude_streaming_queued.txt"))
    assert st.draft == ""  # "Press up to edit queued messages" is a hint
    assert not st.working  # screen alone cannot tell; TerminalManager adds the Claude registry signal
