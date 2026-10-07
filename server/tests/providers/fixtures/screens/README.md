Screen fixtures for `agentdeck.providers.screen`.

- `claude_*.txt`: verbatim `tmux capture-pane -p` output from real Claude Code 2.1.290 sessions
  (captured 2026-10-07 in an isolated tmux socket; Haiku smoke prompts). `❯` is followed by U+00A0 as Claude renders it.
- `codex_idle.txt`, `codex_*_styled.txt`: real Codex 0.160.1 captures (`-e` for styled lines).
- `*_from_binary_strings.txt`: Codex working/approval layouts assembled from the literal UI strings in the
  Codex 0.160.1 binary ("Would you like to run the following command?", "Yes, proceed", "Press enter to confirm or
  esc to cancel", "esc to interrupt"). They were not captured live, to avoid spending Codex quota.
- `claude_streaming_queued.txt`: real Claude Code 2.1.292 capture while it streamed an answer with a queued
  message. No spinner is drawn while text streams, so the screen alone looks idle; `TerminalManager` therefore also
  reads Claude's own `~/.claude/sessions/<pid>.json` (`status: busy`) for managed panes.
