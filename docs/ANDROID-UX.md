# Finding chats and replying

Agent Deck 0.2.0 focuses on the two main phone tasks: find the right chat or child agent, then read or reply to it.

| Earlier screen | Change | Reason |
|---|---|---|
| Live work and older transcripts shared the same long list. | Open chats is the default; All chats includes history. | Current work is easier to find. Idle live chats stay visible, and closed managed panes stay in history. |
| Finding a chat meant scrolling through every project. | Search titles, providers, project paths, agent names, roles and tasks. | A remembered word or folder is enough. Results retain the real parent of a matching child agent. |
| Project headers could not collapse. | Collapse folders and expand child-agent groups separately. | Focus on one project. Searching temporarily reveals matches without changing saved disclosure choices. |
| Several scope and provider filters competed for attention. | Open/All controls plus one provider menu. | Scope and provider are separate choices. Hidden-history and clear-filter actions explain empty results. |
| Folder paths repeated on every chat row. | Show the folder once, with compact chat and child counts. | Chat titles and current activity get more space. Full metadata remains in Session info. |
| A tall send button squeezed the message field. | Full-width multiline composer with a labelled Send button below. | Longer follow-ups are readable. A working session explicitly says sending interrupts current work. |
| Tool output competed with conversation text. | One-line tool summaries with expandable output. | Follow the conversation while retaining access to the complete output. |
| New messages pulled the reader to the bottom. | Follow while at the bottom; show Jump to latest when reading older messages. | Read at your own pace. Streaming updates to the same message are also handled. |

Reply controls continue to use the existing delivery path, including interruption, request deduplication, retained drafts and retry errors. Child agents remain inspect-only with an Open parent chat action. Already-running unmanaged chats do not gain controls through a visual change.

The Fold cover screen shows one pane. Wider windows show the project list beside the selected chat, with a selection marker. Controls use the existing Material palette and system type; no decorative animation was added.

Validation uses model tests, Compose interaction/accessibility tests, and rendered screenshots at 375, 390, 768 and 1440 dp, landscape, light/dark and enlarged text. These checks do not prove physical-phone keyboard, folding or notification behaviour.

Platform references: [adaptive list-detail layouts](https://codelabs.developers.google.com/jetpack-compose-adaptability), [keyboard insets](https://developer.android.com/develop/ui/compose/system/insets-ui), and [Android touch target guidance](https://support.google.com/accessibility/android/answer/7101858).
