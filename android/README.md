# Agent Deck — Android

Native Kotlin/Compose app (`de.finn.agentdeck`, minSdk 26, compile/target SDK 36) for checking and steering
Claude Code / Codex sessions on the Mac helper over Tailscale HTTPS.

## Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug assembleRelease lintDebug test
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true   # also writes screenshots to ../outputs/screenshots/android/
```
- Push: if `app/google-services.json` exists the Google Services plugin is applied and `BuildConfig.PUSH_CONFIGURED=true`.
  Without it the build is explicitly push-unconfigured (FCM service disabled in the manifest, Settings says so).
- Release is built unsigned (no keystore in the repo). Sign with your own keystore before distributing.
- Version pins: AGP 9.4.1 / Gradle 9.6.0 / Kotlin 2.4.20. Compose BOM 2026.06.01, core 1.18, lifecycle 2.10,
  OkHttp 5.4 are the newest lines that still compile against SDK 36 (newer ones require compileSdk 37).

## Modules
| Module | Contents |
|---|---|
| `core:model` | Contract JSON models (`Session` incl. subagent fields, `ServerStatus`, …) and `SessionGrouping` (project → parent → child agents). Pure JVM. |
| `core:api` | `AgentDeckClient` (all REST routes incl. `/status`, `/push/test`, `/resume`, `DELETE /devices`), `EventStream` (SSE + backoff), `ServerUrl`/`PairingLink` (HTTPS only). Pure JVM, OkHttp. |
| `core:push` | `PushCrypto` (AES-256-GCM v1), `PushPayload`, `NotificationPlanner` + `NotificationGate` (silent progress, alert on completed/error/input, no invented percentages, dedupe). Pure JVM. |
| `app` | Compose UI (adaptive cover/inner layouts), Keystore credential store, drafts, repository (SSE foreground + polling fallback), FCM service, notifications, RemoteInput reply via WorkManager. |

## Customising
UI lives in `app/src/main/kotlin/de/finn/agentdeck/ui/` as stateless composables (`*Pane`, `*Screen`) driven by
`*Ui` data classes, so screens can be changed and previewed/screenshot-tested without a server.
Theme tokens: `ui/theme/Theme.kt`. Two-pane breakpoint: `TwoPaneMinWidth` in `ui/AppRoot.kt`.
`tools/make_push_fixture.py` regenerates the shared encryption vectors (same file as `server/tests/fixtures/push-fixture.json`).
