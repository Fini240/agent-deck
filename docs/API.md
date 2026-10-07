# Agent Deck API

Private HTTPS through Tailscale Serve; localhost helper port18787, HTTPS port10443. Pairing is required for chats and controls. Dates are ISO8601 UTC.

## Shared JSON models
Dates are ISO8601 UTC. Extra optional fields tolerated; Kotlin JSON parser ignoreUnknownKeys.
Session: {id,agent:'claude'|'codex',nativeId:string|null,title,cwd,model:string|null,status:'working'|'idle'|'needs_input'|'completed'|'error'|'unknown'|'offline',stage:string|null,progress:Progress|null,updatedAt,managed:boolean,capabilities:{send:boolean,interrupt:boolean,approve:boolean,stop:boolean},lastMessage:string|null,unread:number}.
Progress: {current:number,total:number,unit:string|null}; unknown progress is null, not an invented percentage.
Message: {id,role:'user'|'assistant'|'tool'|'system',text,timestamp,toolName:string|null}.
Approval: {id,sessionId,title,detail,choices:[{id,label}],createdAt}. Choice IDs map only to currently observed TUI choices. Revalidate prompt fingerprint before sending; reject stale approvals.
Models response: {agents:[{id,name,available:boolean,version,models:[{id,label,description:string|null,source}],modelSource,modelRefreshedAt,error:string|null}],refreshedAt}. Live discovery preferred; caches explicitly labelled; manual exact model ID entry must remain possible.

## REST contract
GET /health => {status,version}; public minimal only.
POST /api/v1/pair => body {code,deviceName,fcmToken?:string}; response {deviceId,token,pushKey,serverName}. pushKey base64 raw 32-byte AES key. Store Android device credentials protected by Keystore; backend device keys on disk mode0600. Rate-limit enrollment; code single-use, expiry; auth protects every other route.
GET /api/v1/sessions?agent=all&scope=all => {sessions:Session[]}.
GET /api/v1/sessions/{id}/messages?limit=100 => {messages:Message[]}.
GET /api/v1/sessions/{id}/terminal => {text:string} bounded terminal preview with sensitive values redacted.
GET /api/v1/sessions/{id}/approvals => {approvals:Approval[]}.
POST /api/v1/sessions => {agent,model?:string,cwd,prompt} -> {session:Session}.
POST /api/v1/sessions/{id}/send => {text,interrupt:true,requestId:string} -> {accepted:true,sessionId}. Idempotent request IDs. Interrupt ONLY if working; avoid Ctrl-C twice which exits idle Claude. Wait for real input readiness, then paste with bracketed-paste safely; do not send user newline content as shell commands. API must never target an unrelated tmux pane.
POST /api/v1/sessions/{id}/stop => {requestId:string} -> {accepted:true}; interrupt current work, preserve conversation.
POST /api/v1/sessions/{id}/approvals/{approvalId} => {choiceId,requestId} -> {accepted:true}; reject stale prompt.
POST /api/v1/sessions/{id}/input => {key:'up'|'down'|'enter'|'escape'|'tab'} -> {accepted:true}; managed sessions only, audited explicit terminal controls as fallback.
GET /api/v1/models and POST /api/v1/models/refresh => Models response.
GET /api/v1/settings => {settings:{allowedWorkspaces:string[],defaultAgent,defaultModels:{claude?:string,codex?:string},notifyOn:string[],progressIntervalSeconds:number,keepAwakeMode:'active'|'plugged_in'|'off'}}.
PATCH /api/v1/settings => safe subset, same response; no secret/executable settings via network.
PUT /api/v1/devices/{deviceId}/fcm-token => {fcmToken}; device can update only itself.
GET /api/v1/events => SSE with `event: update` and JSON {type:'sessions'|'messages'|'models'|'approval'|'notification',sessionId?:string,data?:object}. Android SSE used while app foreground; retries and polling fallback. Never cache private responses in a public app service worker.
POST /api/v1/notify => scoped local helper token, body {sessionId,title,body,kind:'progress'|'completed'|'error'|'input',progress?:Progress,stage?:string}; explicit agent notification CLI.
Errors: HTTP appropriate + {error:{code,message}}; mobile shows errors and preserves unsent draft.

## Encrypted FCM
FCM `data` string map keys `v:'1'`, `nonce:<base64 12 bytes>`, `ciphertext:<base64 AES-256-GCM ciphertext with 16-byte tag appended>`. NO AAD for v1. Per-device pushKey. Plain JSON before encryption: {eventId,type:'progress'|'completed'|'error'|'input',sessionId,title,body,agent,timestamp,progress:Progress|null,stage:string|null}. Encrypt entire content. Alert only completed/error/input; progress silent, throttled, setOnlyAlertOnce, indeterminate when total unknown. Android notification RemoteInput replies call authenticated same-session send API via WorkManager, avoid duplicate sends. Permission notification opens chat/app; no approve shortcut. Android 16 promoted Live Updates are conditional; standard ongoing progress notifications must work independently. Firebase initialization configurable and failure clearly surfaced. No analytics SDK.


## Browser dashboard

`GET /` or `/dashboard`: static dashboard shell. `GET /web/apk-info` and `/apk`: latest signed APK metadata/download, available before pairing inside the tailnet. `POST /web/pair`: one-time code exchange; returns browser identity, sets an HttpOnly/Secure/SameSite=Strict cookie and does not expose device secrets to JavaScript. `GET /web/me`: browser identity. `POST /web/logout`: revokes this browser and clears its cookie.

Cookie-authenticated mutations, browser pairing and logout require `X-AgentDeck-Web: 1` and reject cross-origin requests. Native clients continue to use Bearer tokens. Foreground dashboard polling runs every five seconds. All responses are no-store.
