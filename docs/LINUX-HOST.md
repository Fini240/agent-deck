# Independent Linux host

Each helper is independent: its own workspace, session history, CLI login, paired phone tokens and FCM key. Android can save up to eight helpers and switch between them. Selecting another host does not move a running task or its files.

On a Docker-capable Linux server, copy the source checkout into a private directory and run `server/scripts/install-linux.sh /DATA/AppData/agent-deck https://your-host.tailnet.ts.net:10443 YourHostName` as an administrator. It creates private persistent directories, sets workspace permissions, and builds only the Agent Deck container. Re-running preserves existing configuration, credentials and workspace files. The Dockerfile pins the CLI versions tested for this release; change its build arguments to upgrade them later. Existing host services and other containers are not used or modified.

The container uses host networking so Tailscale Serve on the host can reach the helper's **loopback-only** listener on 127.0.0.1:18787. Do not publish it on all interfaces. In `home/.agent-deck/config.json`, set `publicBaseUrl` to your private HTTPS Tailscale address, `workspaceRoots` to `["/workspace"]`, and `apkDir` to `/downloads`. Configure the normal Tailscale Serve daemon with `tailscale serve --bg --https=10443 http://127.0.0.1:18787`; pick a free HTTPS port and preserve unrelated routes. No Funnel is needed.

Log into subscriptions independently:

```
docker exec -it agentdeck codex login --device-auth
docker exec -it agentdeck claude auth login --claudeai
```

New installations seed `home/.codex/config.toml` with `sandbox_mode = "danger-full-access"`, `approval_policy = "on-request"`, and trusted `/workspace`. Docker remains the isolation boundary: non-root user, dropped capabilities, no privilege escalation, and only the dedicated home/workspace mounts. Codex can run commands, edit its writable files and use the network inside that container. It cannot thereby administer the host or other containers. The mode removes Codex's inner filesystem/network boundaries; `on-request` is not a per-command confirmation guarantee.

Codex's default inner Linux sandbox can fail here with `bwrap: No permissions to create a new namespace`, even for `pwd`. Do not make the container privileged to fix this. The installer preserves an existing Codex configuration, so older installations need the two top-level settings above added to their dedicated config (before any TOML table), preserving other preferences. New chats pick up the change. For an already-open managed chat, enter `/permissions`, select **Full Access**, and confirm; that selection also disables approval prompts for that session. Alternatively resume the conversation with the updated configuration. These settings apply only to this Docker deployment; normal Mac Codex permissions stay as configured.

Do not mount another running agent's refreshing auth.json into this container. Secrets belong only in the private persistent home or runtime.env, never the source checkout. For encrypted push, supply the same Firebase project's service account as `home/.agent-deck/firebase-service-account.json` (0600) and the same push-enabled signed Android APK in `downloads`.

Create a fresh pairing code with `docker exec agentdeck agentdeck-admin pair`. On the phone use the device switcher, **Add device**, and scan the QR or enter the URL and code. The earlier Mac pairing stays saved. Pairing codes expire after five minutes. If access to either helper is offline, its tasks and saved drafts remain on that host while the other can be used.

To attach a phone-started session from SSH, use `docker exec -it agentdeck ad` and choose the session. A container restart stops its live terminal processes; conversations remain in the persistent home and can be resumed. Linux sleep prevention is disabled in this deployment because the container does not control the host sleep service.
