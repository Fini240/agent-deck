# Agent Deck — Mac helper server

FastAPI service that lets the Agent Deck Android app watch and control Claude Code / Codex
terminal sessions on this Mac. It listens on **127.0.0.1:18787 only**; the phone reaches it
through Tailscale Serve (tailnet-only HTTPS on :10443). Nothing listens on a public interface.

## Install / run

```bash
cd server
./scripts/install.sh            # venv + deps + ~/.agent-deck (0700) + local token; idempotent
./scripts/start-server.sh       # foreground, Ctrl-C to stop
./scripts/install-launchd.sh    # optional: LaunchAgent de.finn.agentdeck (start at login, restart on crash)
./scripts/install-launchd.sh --uninstall
./scripts/tailscale-serve.sh    # shows the plan; --apply adds https:10443 -> 127.0.0.1:18787, --remove undoes it
```

None of these scripts edit shell startup files. The normal `claude` / `codex` commands and
their subscriptions are untouched; the server never needs an API key.

## Pair a phone

```bash
./scripts/agentdeck-admin.sh pair        # terminal QR with agentdeck://pair?server=...&code=...
./scripts/agentdeck-admin.sh devices     # list paired devices (no secrets printed)
./scripts/agentdeck-admin.sh revoke dev_xxxxxxxx
./scripts/agentdeck-admin.sh apk-link    # 30-min private link to the APK in ../outputs
./scripts/agentdeck-admin.sh status      # port, public URL, push availability
./scripts/agentdeck-admin.sh set-public-url https://<mac>.<tailnet>.ts.net:10443
```

Pairing codes are 12 characters, single-use, expire after 5 minutes and are stored only as
hashes. The app exchanges the code at `POST /api/v1/pair` for a device token (stored hashed)
and a per-device 32-byte AES push key. Enrollment is rate-limited (10 attempts / 10 min).

## Files in `~/.agent-deck` (override with `AGENTDECK_HOME`)

| File | Purpose | Mode |
|---|---|---|
| `config.json` | local-only server config (see below) | 0600 |
| `settings.json` | phone-editable preferences (`PATCH /api/v1/settings`) | 0600 |
| `devices.json` | device id, name, token **hash**, push key, FCM token | 0600 |
| `pairing.json` | pending pairing code hashes + expiry | 0600 |
| `local-token` | bearer token for local CLIs (`POST /api/v1/notify`) | 0600 |
| `firebase-service-account.json` | FCM sender credentials (refused unless 0600) | 0600 |
| `idempotency.json` | last 1000 request IDs (24 h) for bounded, device-specific retry protection | 0600 |
| `download-links.json` | hashed, expiring APK link tokens | 0600 |
| `terminal/` | TerminalManager state (provider-owned) | 0700 |
| `logs/audit.jsonl` | control actions (no message text), 1 MB + 1 rotation | 0600 |
| `logs/server.log` | with `--log-file`: 1 MB × 4 rotation | 0600 |

`config.json` keys (all optional): `port`, `publicBaseUrl`, `extraAllowedHosts`,
`workspaceRoots` (default: your home), `pollIntervalSeconds` (3), `completionDebounceSeconds` (6),
`notifyActivityLeaseSeconds` (600), `apkDir` (default `<repo>/outputs`), `maxSseClients` (8),
`fcm: {enabled, serviceAccountFile, projectId}`.

## Push (FCM)

Credentials are read from `fcm.serviceAccountFile` (default `~/.agent-deck/firebase-service-account.json`).
If missing, unreadable, not 0600 or disabled, `/api/v1/status` reports `push.available=false` with
a reason and nothing pretends to be delivered. Every FCM message is a data-only message:

```json
{"v": "1", "nonce": "<b64 12 bytes>", "ciphertext": "<b64 AES-256-GCM ct||tag>"}
```

No AAD; plaintext is the contract JSON `{eventId,type,sessionId,title,body,agent,timestamp,progress,stage}`.
There is no `notification` block, so Google only sees ciphertext. Progress is collapsed per
session and throttled to `progressIntervalSeconds`; only `completed`/`error`/`input` should alert.
`tests/fixtures/push-fixture.json` is the same vector file the Android tests use.
`POST /api/v1/push/test` sends an encrypted test to the calling device.

## Status machine & keep-awake

The monitor polls providers + managed terminals every 3 s. Per session:

* first observation = baseline (no notification after a restart);
* `working → idle|completed` alerts **completed** only after the new state held for 6 s
  (TUIs flicker idle between tool calls);
* `→ error` alerts **error**, `→ needs_input` or a new approval prompt alerts **input**;
* `unknown`/`offline` never alert and never erase the last definite state — a quiet
  transcript is not treated as finished;
* subagent rows never alert or hold the Mac awake (the parent does).

`caffeinate -i -w <server pid>` (or `-s` in `plugged_in` mode) runs only while a session is
confirmed `working` or an agent sent a `progress` notify within the last 10 min. It is
released on idle, error, needs-input, `keepAwakeMode: off`, and shutdown; `-w` also ends it if
the server is killed. The display may still sleep. Only our own child process is ever stopped.
On Linux the same logic uses `systemd-inhibit`.

## API

See [`docs/API.md`](../docs/API.md). Additions beyond the contract (all optional):

* `GET /api/v1/status` — push / keep-awake / provider / terminal / monitor health.
* `POST /api/v1/sessions/{id}/resume` — adopt an existing chat into a managed terminal once;
  refused for live/working chats and subagents.
* `GET /api/v1/notifications?limit=50`, `POST /api/v1/push/test`, `DELETE /api/v1/devices/{self}`.
* Session extras: `parentSessionId, agentName, agentRole, isSubagent, task, projectRoot,
  statusEvidence, live, canResume`.
* `GET /download/{token}` — expiring private APK page (created by `apk-link`).

Security: chats and controls need a device Bearer token or paired-browser cookie; `/notify` needs the local token. The dashboard shell, web APK routes, health and code-exchange routes are reachable without device authentication inside the tailnet. Cookie mutations require a custom header and same-origin checks. Host header allowlist (localhost + the Tailscale
name) blocks DNS-rebinding. Sent text with ESC/C0/C1 control characters is rejected so it can't
escape bracketed paste; model IDs and workspace paths are validated (realpath inside allowed
workspaces, never `~/.ssh`, `~/.agent-deck`, `/`). Control actions use per-session locks and
idempotent request IDs retained for 24 hours; responses are `Cache-Control: no-store`; tracebacks never reach the phone.
Terminal previews, messages and push bodies are redacted for common key/token formats.

## Tests

```bash
cd server && .venv/bin/python -m pytest -q
```
