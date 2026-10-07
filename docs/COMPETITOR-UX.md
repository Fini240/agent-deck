# Mobile agent UX review — 7 October 2026

Reviewed official product documentation and repositories. This is a workflow comparison, not hands-on device testing of the other apps. No competitor source code or assets were copied.

| App | Documented pattern | Agent Deck decision |
| --- | --- | --- |
| [GitHub Mobile](https://github.blog/changelog/2026-04-01-github-mobile-stay-in-flow-with-a-refreshed-copilot-tab-and-native-session-logs/) | Dedicated session overview, state filters and native logs. | Add an accessible activity filter and a direct attention-count shortcut. Make assistant replies easier to scan. |
| [Termius Workspaces](https://termius.com/blog/workspaces-focus-without-losing-context) | Related terminals grouped by intent, focused session with other work visible, remembered workspaces. | Keep the existing project → chat → true child-agent hierarchy and Fold two-pane layout. Add device-local pinned chats, isolated per paired Mac. |
| [HAPI](https://raw.githubusercontent.com/tiann/hapi/main/web/README.md) | Session status and approval visibility, streaming conversation and composer with drafts separate from queued work. | Focus on waiting requests and errors. Quick reply choices fill an editable draft and require explicit Send. |
| [Happy](https://raw.githubusercontent.com/slopus/happy/main/README.md) | Phone access to normal Claude/Codex CLI work, continuing a session across devices. | Preserve Agent Deck's existing same-session terminal controls and installed CLI authentication. No new provider account or model-ID list. |

## Implemented in Android 0.2.3

- **Needs you:** waiting-input sessions and errors, with current scope/provider/search/pin filters respected. Open chats excludes old non-live errors; All chats can include history. Matching child agents and folders reveal themselves while this filter is active without changing saved disclosure state. Counts include each matching session once and exclude context-only parents.
- **Pins:** star/unstar a top-level chat without opening it. A pinned chat keeps its child agents reachable. Pinned filters and ordinary scope/search/provider/activity filters compose. Pins persist on the phone per paired Mac URL; within an activity rank they appear first in their project. Waiting requests retain their priority. Unknown or removed IDs create no fake sessions. At most 100 pins per Mac.
- **Readable replies:** a small Markdown subset for assistant/system messages: headings, bullets, quotes, rules, bold, inline code and fenced code. Copy code copies only the code; long lines scroll sideways. User text stays literal and tool output remains expandable. Unsupported tables, links, italics and indented code stay literal. Unclosed fenced code remains visible and is labelled as still being written.
- **Quick reply:** a menu on an empty draft offers progress, summary, input-needed and continue prompts. It only prepares text, never sends or replaces a draft. Existing interrupt/retry/idempotency controls remain in the detail view model. Read-only and child-agent conversations retain their parent-chat route.

No server API, terminal management, notification routing or provider model discovery changes are needed for these improvements. Installer confirmation remains Android's responsibility. The same existing signing key and private APK update channel are retained.

## Deferred

Voice input, Git diff browsing, automatic command broadcast and custom workspace templates need separate interaction and backend work. This release addresses finding a conversation and replying. It does not claim a speed or battery improvement without measurements.

## Validation

Parser and Compose interaction tests cover code copy, draft protection, explicit Send, attention reveal, filter combinations, parent context and pin persistence across paired Macs. Native composables are rendered at phone, Fold-inner and wide widths, light/dark and enlarged system text. These checks do not establish physical Fold keyboard, folding, notification or installation behavior.
