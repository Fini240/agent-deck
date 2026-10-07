# Agent Deck

A native Android app and mobile web dashboard for checking and steering Claude Code and Codex terminal sessions on your Mac.

## Features

- Project folders with expandable real subagents, each with its own task, messages, tool activity and status.
- Follow-up messages to the same managed terminal session, with interruption enabled by default; stop controls and permission approvals.
- New chats with models discovered from your installed CLIs, plus exact custom model IDs.
- Private HTTPS through Tailscale, device pairing and revocation.
- Android encrypted FCM notifications: silent activity/progress updates, completion/error/input alerts and direct notification replies.
- Real progress totals when available; an activity indicator when no percentage is known.
- A browser dashboard with a signed APK download button, usable before browser pairing.
- In-app APK updates from the paired Mac: check, verified download progress and Android installation confirmation.
- Native Compose layouts for phone and larger screens, with editable source throughout.

Agent Deck uses your installed terminal CLIs and their normal authentication. It does not require provider API keys. Existing unmanaged chats are inspectable; controls require a managed session. Active unmanaged chats are never silently cloned. Child agents are inspectable and steered through their parent.

## Mac helper and dashboard

Requirements: macOS, Python 3.11+, tmux, Tailscale, and authenticated Claude Code/Codex CLIs.

```sh
git clone https://github.com/Fini240/agent-deck.git
cd agent-deck/server
./scripts/install.sh --dev
./scripts/install-launchd.sh
./scripts/tailscale-serve.sh --apply
```

The helper binds only to `127.0.0.1:18787`. Tailscale Serve exposes private HTTPS on 10443 and leaves existing 443 routes unchanged. Open `https://<mac>.<tailnet>.ts.net:10443/` from another device on the same tailnet.

Create a five-minute, single-use pairing code:

```sh
./scripts/agentdeck-admin.sh pair
```

Enter it under **Use this browser**, or scan it with the Android app. Browser and Android enrollments each need a fresh code. Browser tokens stay in Secure/HttpOnly cookies. The Android app protects credentials through Android Keystore.

To put normal terminal commands in managed sessions, review and install the optional shell block:

```sh
.venv/bin/python -m agentdeck.cli install-shell ~/.zshrc --dry-run
.venv/bin/python -m agentdeck.cli install-shell ~/.zshrc
```

Open a new shell afterward. `AGENTDECK_DISABLE=1` bypasses the helper for one invocation; `Ctrl-b d` detaches from tmux while leaving work running. Native executable paths can be overridden with `AGENTDECK_CLAUDE_BIN` and `AGENTDECK_CODEX_BIN`.

To get back into a running managed session, run `agentdeck attach`. In a terminal it shows a menu of the open Claude/Codex sessions with provider, status, title, project directory and an `[attached]` marker. Use Up/Down (or `j`/`k`, or a number) and Enter to attach; `q`, Escape or `Ctrl-C` cancels. On a `TERM=dumb` terminal it asks for a number instead. `agentdeck attach SESSION_ID` attaches directly. Without a TTY it attaches the only session, or prints the sessions as JSON when there are several.

The shell block also defines the shortcut `ad`: plain `ad` opens the session menu (same as `agentdeck attach`), and `ad` with arguments forwards them to `agentdeck`, e.g. `ad pair`.

## Android APK

Version 0.2.0 adds a searchable Open chats view, collapsible project folders, clearer child-agent groups and a full-width reply composer. See [the Android usability changes](docs/ANDROID-UX.md).

Requirements: JDK 21 and Android SDK 36. Set `JAVA_HOME` and your SDK location (`ANDROID_HOME` or an untracked `android/local.properties`), then:

```sh
cd android
./gradlew assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. To make it available through your dashboard, copy a versioned, signed APK into the repository's `outputs/` directory:

```sh
mkdir -p outputs
cp android/app/build/outputs/apk/debug/app-debug.apk outputs/agent-deck-0.2.2.apk
```

Run those copy commands from the repository root. `/apk` excludes unsigned and push-unconfigured builds. Release builds are unsigned until you configure your own signing key.

### Optional background push

Use your own Firebase project. Add its Android configuration to `android/app/google-services.json` (package `de.finn.agentdeck`) before building. Put an FCM sender service account at `~/.agent-deck/firebase-service-account.json` with mode 0600, then configure its project in the helper's local config. Both files are ignored by Git. Without Firebase configuration, the Android app builds with push explicitly disabled; foreground chat access still works. No Analytics SDK is included.

Only encrypted data payloads are sent through FCM. Chat traffic and remote controls use your private Tailscale connection.

## Customize

- `server/agentdeck/web_static/`: dashboard HTML, CSS and JavaScript; no frontend build step or CDN.
- `android/app/src/main/kotlin/de/finn/agentdeck/ui/`: Compose screens and theme.
- `server/agentdeck/providers/`: native session and model discovery.
- `server/agentdeck/terminal.py`: isolated tmux session controls.
- `docs/API.md`: API contract.

See [server setup](server/README.md) and [Android build notes](android/README.md).

## Validation and current limits

The development build passed 206 server tests (one opt-in live-discovery skip) and 93 Android tests. Browser checks covered light/dark layouts at 390/768/1440, grouping, draft retention, same-ID retries, pairing and APK integrity. Private HTTPS pairing and download were verified. This public snapshot also passes all 206 server tests (one opt-in skip) and 16 Android API tests.

Physical Android push delivery, notification replies, folding and live Codex approval dialogs still need device acceptance. Some new Codex sessions cannot expose a proven native transcript identity; their managed terminal preview remains available. Web updates run while the page is visible; background notifications use the Android app.

## Test

```sh
cd server
./scripts/install.sh --dev
.venv/bin/python -m pytest -q
```

```sh
cd android
./gradlew test lintDebug
```

Private device state, service-account credentials, signing keys, generated builds, chat data and internal coordination/history are not part of this repository.

APK update setup and future release delivery: [In-app updates](docs/APP-UPDATES.md).
