"""FastAPI application: REST + SSE API for the Agent Deck phone app."""

from __future__ import annotations

import asyncio
import contextlib
import html
import json
import logging
import time
from pathlib import Path
from typing import Any, Callable

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from . import config as cfgmod
from . import web
from .auth import DeviceStore, RateLimiter, check_local_token, utc_iso
from .downloads import DownloadTokens, find_apks
from .errors import (
    AgentDeckError,
    AuthError,
    ForbiddenError,
    InvalidInputError,
    NotFoundError,
    NotManagedError,
    NotReadyError,
    StaleApprovalError,
    UnavailableError,
    UnsupportedError,
)
from .events import CLOSE, EventBus, format_sse
from .idempotency import IdempotencyStore
from .keepawake import KeepAwake
from .monitor import SessionHub, clean_progress, normalize_session, public_session
from .push import FcmSender, PushService
from .security import (
    APPROVAL_ID_RE,
    REQUEST_ID_RE,
    SESSION_ID_RE,
    bounded_preview,
    check_id,
    clean_send_text,
    redact,
)
from .storage import BoundedJsonl, ensure_private_dir

log = logging.getLogger("agentdeck.app")

MAX_BODY_BYTES = 256 * 1024
MAX_STREAMS_PER_DEVICE = 2
INPUT_KEYS = ("up", "down", "enter", "escape", "tab")
MESSAGE_ROLES = ("user", "assistant", "tool", "system")


def _load_default_providers() -> tuple[Any | None, str | None]:
    try:
        from . import providers  # type: ignore[attr-defined]
    except Exception as exc:  # provider package not built yet / broken
        return None, f"providers unavailable ({type(exc).__name__})"
    missing = [f for f in ("discover_sessions", "read_messages", "discover_models") if not hasattr(providers, f)]
    if missing:
        return None, f"providers incomplete (missing {', '.join(missing)})"
    return providers, None


def _load_default_terminal(state_dir: Path) -> tuple[Any | None, str | None]:
    try:
        from .terminal import TerminalManager  # type: ignore[attr-defined]
    except Exception as exc:
        return None, f"terminal manager unavailable ({type(exc).__name__})"
    try:
        return TerminalManager(state_dir), None
    except Exception as exc:
        return None, f"terminal manager failed to start ({type(exc).__name__})"


def _translate(exc: Exception) -> AgentDeckError:
    if isinstance(exc, AgentDeckError):
        return exc
    if isinstance(exc, (KeyError, LookupError)):
        return NotFoundError("session or item not found")
    if isinstance(exc, ValueError):
        return InvalidInputError(str(exc)[:200] or "invalid input")
    if isinstance(exc, PermissionError):
        return ForbiddenError("not permitted")
    if isinstance(exc, TimeoutError):
        return NotReadyError("terminal did not become ready in time")
    if isinstance(exc, NotImplementedError):
        return UnsupportedError("not supported for this session")
    log.exception("unexpected error", exc_info=exc)
    return AgentDeckError("internal error")


def _error_response(err: AgentDeckError) -> JSONResponse:
    return JSONResponse({"error": {"code": err.code, "message": err.message}}, status_code=err.status)


def create_app(
    *,
    home: Path | None = None,
    server_config: cfgmod.ServerConfig | None = None,
    providers: Any = "default",
    terminal: Any = "default",
    push_sender: Any | None = None,
    keepawake: Any | None = "default",
    clock: Callable[[], float] = time.time,
    start_monitor: bool = True,
    async_push: bool = True,
    extra_hosts: list[str] | None = None,
    detect_tailscale: bool = True,
) -> FastAPI:
    home = home or cfgmod.home_dir()
    scfg = server_config or cfgmod.load_server_config()
    local_token = cfgmod.ensure_local_token()

    providers_error = None
    if providers == "default":
        providers, providers_error = _load_default_providers()
    terminal_error = None
    if terminal == "default":
        terminal, terminal_error = _load_default_terminal(ensure_private_dir(home / "terminal"))
    if keepawake == "default":
        keepawake = KeepAwake()

    devices = DeviceStore(home, clock=clock)
    sender = push_sender or FcmSender(scfg.service_account_path(), scfg.fcm.project_id, scfg.fcm.enabled)
    push = PushService(devices, sender, clock=clock)
    bus = EventBus()
    idem = IdempotencyStore(home / "idempotency.json", clock=clock)
    audit = BoundedJsonl(home / "logs" / "audit.jsonl", max_bytes=1_000_000)
    downloads = DownloadTokens(home, clock=clock)
    settings_state = {"value": cfgmod.load_settings()}

    hub = SessionHub(
        providers=providers,
        terminal=terminal,
        get_settings=lambda: settings_state["value"],
        push=push,
        bus=bus,
        keepawake=keepawake,
        debounce=scfg.completion_debounce_seconds,
        lease_seconds=scfg.notify_activity_lease_seconds,
        clock=clock,
        async_push=async_push,
        providers_error=providers_error,
    )

    pair_limiter = RateLimiter(10, 600)
    auth_fail_limiter = RateLimiter(60, 60)
    download_limiter = RateLimiter(30, 600)

    allowed_hosts = {"localhost", "127.0.0.1", "::1", "[::1]", *(extra_hosts or []), *scfg.extra_allowed_hosts}
    if scfg.public_base_url:
        from urllib.parse import urlsplit

        if urlsplit(scfg.public_base_url).hostname:
            allowed_hosts.add(urlsplit(scfg.public_base_url).hostname)

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        bus.bind_loop(asyncio.get_running_loop())
        if detect_tailscale and not scfg.public_base_url:
            url = await run_in_threadpool(cfgmod.detect_tailscale_base_url)
            if url:
                from urllib.parse import urlsplit

                allowed_hosts.add(urlsplit(url).hostname or "")
                app.state.public_base_url = url
        task = None
        if start_monitor:
            task = asyncio.create_task(_poll_loop())
        try:
            yield
        finally:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await task
            hub.shutdown()
            close = getattr(terminal, "close", None)
            if callable(close):
                with contextlib.suppress(Exception):
                    close()

    async def _poll_loop() -> None:
        while True:
            try:
                await run_in_threadpool(hub.poll_once)
            except Exception:
                log.exception("monitor poll failed")
            await asyncio.sleep(max(1.0, scfg.poll_interval_seconds))

    app = FastAPI(title="Agent Deck", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.hub = hub
    app.state.devices = devices
    app.state.push = push
    app.state.bus = bus
    app.state.downloads = downloads
    app.state.public_base_url = scfg.public_base_url
    app.state.allowed_hosts = allowed_hosts

    # ------------------------------------------------------------ plumbing
    @app.middleware("http")
    async def guard(request: Request, call_next):
        if _host_only(request.headers.get("host") or "").lower() not in allowed_hosts:
            return _error_response(AgentDeckError("unexpected Host header", code="bad_host", status=421))
        length = request.headers.get("content-length")
        if length and (not length.isdigit() or int(length) > MAX_BODY_BYTES):
            return _error_response(InvalidInputError("request body too large", status=413))
        response = await call_next(request)
        response.headers.setdefault("Cache-Control", "no-store")
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        return response

    @app.exception_handler(AgentDeckError)
    async def _deck_error(_: Request, exc: AgentDeckError):
        return _error_response(exc)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError):
        return _error_response(InvalidInputError("invalid request"))

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException):
        code = {404: "not_found", 405: "method_not_allowed"}.get(exc.status_code, "http_error")
        return _error_response(AgentDeckError(str(exc.detail), code=code, status=exc.status_code))

    @app.exception_handler(Exception)
    async def _unexpected(_: Request, exc: Exception):
        return _error_response(_translate(exc))

    async def body_json(request: Request) -> dict[str, Any]:
        raw = await request.body()
        if len(raw) > MAX_BODY_BYTES:
            raise InvalidInputError("request body too large", status=413)
        if not raw:
            return {}
        try:
            data = json.loads(raw)
        except ValueError:
            raise InvalidInputError("body must be JSON") from None
        if not isinstance(data, dict):
            raise InvalidInputError("body must be a JSON object")
        return data

    def bearer(request: Request) -> str:
        header = request.headers.get("authorization") or ""
        scheme, _, token = header.partition(" ")
        return token.strip() if scheme.lower() == "bearer" else ""

    def require_device(request: Request) -> dict[str, Any]:
        # Native clients use Bearer; a paired browser falls back to its HttpOnly cookie
        # (same-origin + X-AgentDeck-Web checks happen before the token is used).
        token, _ = web.device_credentials(request)
        try:
            dev = devices.authenticate(token)
        except AuthError:
            auth_fail_limiter.check()
            raise
        devices.touch(dev["deviceId"])
        request.state.auth_token = token
        return dev

    async def device_dep(request: Request) -> dict[str, Any]:
        return await run_in_threadpool(require_device, request)

    async def terminal_call(sid: str, fn: Callable[[], Any], lock: bool = True) -> Any:
        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")

        def run():
            if not lock:
                return fn()
            with hub.session_lock(sid):
                return fn()

        try:
            return await run_in_threadpool(run)
        except Exception as exc:
            raise _translate(exc) from None

    async def resolve(sid: str, managed: bool = False, fresh: bool = False) -> dict[str, Any]:
        check_id(sid, SESSION_ID_RE, "session id")
        # Control actions always act on freshly observed state.
        s = await run_in_threadpool(hub.resolve, sid, 0.0 if fresh else 2.0)
        if managed and not s["managed"]:
            raise NotManagedError("this chat is read-only until it is resumed through Agent Deck")
        return s

    def audit_log(device: dict | None, action: str, **fields: Any) -> None:
        audit.append({"ts": utc_iso(clock()), "device": device["deviceId"] if device else "local", "action": action, **fields})

    def idem_key(device: dict, action: str, request_id: Any) -> str:
        check_id(request_id, REQUEST_ID_RE, "requestId")
        return f"{device['deviceId']}:{action}:{request_id}"

    # ---------------------------------------------------------------- public
    @app.get("/health")
    async def health():
        return {"status": "ok", "version": __version__}

    @app.post("/api/v1/pair")
    async def pair(request: Request):
        pair_limiter.check()
        body = await body_json(request)
        result = await run_in_threadpool(
            devices.pair, body.get("code"), body.get("deviceName") or "Android", body.get("fcmToken")
        )
        audit_log({"deviceId": result["deviceId"]}, "pair", name=str(body.get("deviceName") or "")[:80])
        return {**result, "serverName": scfg_server_name()}

    def scfg_server_name() -> str:
        import platform

        return (platform.node() or "Mac").split(".")[0]

    # ---------------------------------------------------------------- status
    @app.get("/api/v1/status")
    async def status(request: Request):
        await device_dep(request)
        st = hub.state
        return {
            "version": __version__,
            "push": await run_in_threadpool(push.status),
            "keepAwake": keepawake.status() if keepawake is not None else {"held": False, "mode": None, "error": "disabled"},
            "providers": {"available": providers is not None, "error": providers_error},
            "terminal": {"available": terminal is not None, "error": terminal_error},
            "monitor": {"refreshedAt": utc_iso(st.refreshed_at) if st.refreshed_at else None, "error": st.error},
            "sseClients": bus.subscriber_count,
        }

    # -------------------------------------------------------------- sessions
    @app.get("/api/v1/sessions")
    async def sessions(request: Request, agent: str = "all", scope: str = "all"):
        await device_dep(request)
        if agent not in ("all", "claude", "codex") or scope not in ("all", "managed", "active"):
            raise InvalidInputError("invalid filter")
        st = await run_in_threadpool(hub.snapshot)
        out = []
        for s in st.sessions:
            if agent != "all" and s["agent"] != agent:
                continue
            if scope == "managed" and not s["managed"]:
                continue
            if scope == "active" and s["status"] not in ("working", "needs_input"):
                continue
            out.append(public_session(s))
        return {"sessions": out}

    @app.post("/api/v1/sessions")
    async def start_session(request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        settings = settings_state["value"]
        agent = body.get("agent") or settings.get("defaultAgent")
        if agent not in cfgmod.AGENTS:
            raise InvalidInputError("agent must be claude or codex")
        model = body.get("model") or settings.get("defaultModels", {}).get(agent)
        if model is not None and (not isinstance(model, str) or not cfgmod.MODEL_ID_RE.match(model)):
            raise InvalidInputError("invalid model id")
        cwd = await run_in_threadpool(cfgmod.resolve_workspace, body.get("cwd"), settings)
        prompt = body.get("prompt")
        prompt = clean_send_text(prompt) if prompt not in (None, "") else None
        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")

        def do_start():
            raw = terminal.start_session(agent, str(cwd), model, prompt)
            if not isinstance(raw, dict) or not raw.get("id"):
                raise AgentDeckError("terminal manager returned no session")
            return public_session(normalize_session(raw, True))

        try:
            if body.get("requestId") is not None:
                key = idem_key(device, "start", body.get("requestId"))
                session = await run_in_threadpool(idem.run, key, do_start)
            else:
                session = await run_in_threadpool(do_start)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "start", sessionId=session["id"], agent=agent, cwd=str(cwd), model=model)
        await run_in_threadpool(hub.refresh)
        bus.publish("sessions")
        return {"session": session}

    @app.get("/api/v1/sessions/{sid}/messages")
    async def messages(sid: str, request: Request, limit: int = 100):
        await device_dep(request)
        s = await resolve(sid)
        limit = max(1, min(int(limit), 500))
        if providers is None:
            raise UnavailableError(providers_error or "providers unavailable")
        try:
            raw = await run_in_threadpool(providers.read_messages, s, limit)
        except Exception as exc:
            raise _translate(exc) from None
        out = []
        for i, m in enumerate(list(raw or [])[-limit:]):
            if not isinstance(m, dict):
                continue
            role = m.get("role") if m.get("role") in MESSAGE_ROLES else "system"
            out.append(
                {
                    "id": str(m.get("id") or f"{s['id']}:{i}"),
                    "role": role,
                    "text": redact(str(m.get("text") or ""))[:20000],
                    "timestamp": m.get("timestamp") or s["updatedAt"],
                    "toolName": m.get("toolName") or None,
                }
            )
        return {"messages": out}

    @app.get("/api/v1/sessions/{sid}/terminal")
    async def terminal_preview(sid: str, request: Request):
        await device_dep(request)
        s = await resolve(sid)
        if not s["managed"] or terminal is None:
            return {"text": "", "available": False}
        text = await terminal_call(s["id"], lambda: terminal.preview(s["id"]), lock=False)
        return {"text": bounded_preview(str(text or "")), "available": True}

    @app.get("/api/v1/sessions/{sid}/approvals")
    async def approvals(sid: str, request: Request):
        await device_dep(request)
        s = await resolve(sid)
        if not s["managed"] or terminal is None:
            return {"approvals": []}
        raw = await terminal_call(s["id"], lambda: terminal.approvals(s["id"]), lock=False)
        return {"approvals": [_approval(a, s["id"]) for a in (raw or []) if isinstance(a, dict) and a.get("id")]}

    def _approval(a: dict, sid: str) -> dict[str, Any]:
        return {
            "id": str(a["id"]),
            "sessionId": sid,
            "title": redact(str(a.get("title") or "Permission requested"))[:300],
            "detail": redact(str(a.get("detail") or ""))[:8000],
            "choices": [
                {"id": str(c.get("id")), "label": str(c.get("label") or c.get("id"))[:120]}
                for c in a.get("choices") or []
                if isinstance(c, dict) and c.get("id") is not None
            ],
            "createdAt": a.get("createdAt") or utc_iso(clock()),
        }

    @app.post("/api/v1/sessions/{sid}/send")
    async def send(sid: str, request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        key = idem_key(device, "send", body.get("requestId"))
        text = clean_send_text(body.get("text"))
        interrupt = body.get("interrupt", True)
        if not isinstance(interrupt, bool):
            raise InvalidInputError("interrupt must be boolean")
        s = await resolve(sid, managed=True, fresh=True)
        if not s["capabilities"]["send"]:
            raise NotManagedError("sending is not available for this session")
        real = s["id"]

        def do_send():
            with hub.session_lock(real):
                terminal.send(real, text, interrupt)
            return {"accepted": True, "sessionId": real}

        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")
        try:
            result = await run_in_threadpool(idem.run, key, do_send)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "send", sessionId=real, chars=len(text), interrupt=interrupt)
        bus.publish("messages", real)
        return result

    @app.post("/api/v1/sessions/{sid}/stop")
    async def stop(sid: str, request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        key = idem_key(device, "stop", body.get("requestId"))
        s = await resolve(sid, managed=True, fresh=True)
        if not s["capabilities"]["stop"]:
            raise NotManagedError("stopping is not available for this session")
        real = s["id"]
        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")

        def do_stop():
            with hub.session_lock(real):
                terminal.stop(real)
            return {"accepted": True}

        try:
            result = await run_in_threadpool(idem.run, key, do_stop)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "stop", sessionId=real)
        bus.publish("sessions")
        return result

    @app.post("/api/v1/sessions/{sid}/approvals/{approval_id}")
    async def respond_approval(sid: str, approval_id: str, request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        key = idem_key(device, "approve", body.get("requestId"))
        check_id(approval_id, APPROVAL_ID_RE, "approval id")
        choice_id = body.get("choiceId")
        if not isinstance(choice_id, str) or not choice_id or len(choice_id) > 160:
            raise InvalidInputError("choiceId required")
        s = await resolve(sid, managed=True, fresh=True)
        if not s["capabilities"]["approve"]:
            raise NotManagedError("approvals are not available for this session")
        real = s["id"]
        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")

        def do_respond():
            with hub.session_lock(real):
                current = terminal.approvals(real) or []
                match = next((a for a in current if isinstance(a, dict) and str(a.get("id")) == approval_id), None)
                if match is None:
                    raise StaleApprovalError("this approval request is no longer pending")
                if choice_id not in {str(c.get("id")) for c in match.get("choices") or [] if isinstance(c, dict)}:
                    raise StaleApprovalError("this choice is no longer offered")
                terminal.respond_approval(real, approval_id, choice_id)
            return {"accepted": True}

        try:
            result = await run_in_threadpool(idem.run, key, do_respond)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "approval", sessionId=real, approvalId=approval_id, choiceId=choice_id)
        bus.publish("approval", real)
        return result

    @app.post("/api/v1/sessions/{sid}/input")
    async def input_key(sid: str, request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        key_name = body.get("key")
        if key_name not in INPUT_KEYS:
            raise InvalidInputError(f"key must be one of {', '.join(INPUT_KEYS)}")
        s = await resolve(sid, managed=True, fresh=True)
        real = s["id"]
        if terminal is None:
            raise UnavailableError(terminal_error or "terminal manager unavailable")

        def do_input():
            with hub.session_lock(real):
                terminal.input_key(real, key_name)
            return {"accepted": True}

        try:
            if body.get("requestId") is not None:
                result = await run_in_threadpool(idem.run, idem_key(device, "input", body.get("requestId")), do_input)
            else:
                result = await run_in_threadpool(do_input)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "input", sessionId=real, key=key_name)
        return result

    @app.post("/api/v1/sessions/{sid}/resume")
    async def resume(sid: str, request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        s = await resolve(sid, fresh=True)
        if s["managed"]:
            return {"session": public_session(s)}
        fn = getattr(terminal, "resume_session", None)
        if terminal is None or not callable(fn):
            raise UnsupportedError("resuming existing chats is not available yet")
        if s.get("isSubagent"):
            raise UnsupportedError("subagents can't be steered directly; reply to the parent chat")
        if s["status"] in ("working", "needs_input") or s.get("live") or not s.get("canResume"):
            raise NotManagedError("this chat is open in another terminal; close it there first to avoid two live agents")
        settings = settings_state["value"]
        if s.get("cwd"):
            await run_in_threadpool(cfgmod.resolve_workspace, s["cwd"], settings)

        def do_resume():
            raw = fn(s)
            if not isinstance(raw, dict) or not raw.get("id"):
                raise AgentDeckError("terminal manager returned no session")
            return {"session": public_session(normalize_session(raw, True))}

        try:
            if body.get("requestId") is not None:
                result = await run_in_threadpool(idem.run, idem_key(device, "resume", body.get("requestId")), do_resume)
            else:
                result = await run_in_threadpool(do_resume)
        except Exception as exc:
            raise _translate(exc) from None
        audit_log(device, "resume", sessionId=s["id"], newSessionId=result["session"]["id"])
        await run_in_threadpool(hub.refresh)
        bus.publish("sessions")
        return result

    # ----------------------------------------------------------------- models
    @app.get("/api/v1/models")
    async def models(request: Request):
        await device_dep(request)
        try:
            return await run_in_threadpool(hub.models, False)
        except Exception as exc:
            raise _translate(exc) from None

    @app.post("/api/v1/models/refresh")
    async def models_refresh(request: Request):
        await device_dep(request)
        try:
            return await run_in_threadpool(hub.models, True)
        except Exception as exc:
            raise _translate(exc) from None

    # --------------------------------------------------------------- settings
    @app.get("/api/v1/settings")
    async def get_settings(request: Request):
        await device_dep(request)
        return {"settings": settings_state["value"]}

    @app.patch("/api/v1/settings")
    async def patch_settings(request: Request):
        device = await device_dep(request)
        body = await body_json(request)
        patch = body.get("settings", body) if isinstance(body.get("settings"), dict) else body
        new = await run_in_threadpool(cfgmod.validate_settings_patch, patch, settings_state["value"], scfg)
        await run_in_threadpool(cfgmod.save_settings, new)
        settings_state["value"] = new
        audit_log(device, "settings", keys=sorted(patch))
        await run_in_threadpool(hub._update_keepawake)
        return {"settings": new}

    # ---------------------------------------------------------------- devices
    @app.put("/api/v1/devices/{device_id}/fcm-token")
    async def fcm_token(device_id: str, request: Request):
        device = await device_dep(request)
        if device_id != device["deviceId"]:
            raise ForbiddenError("a device can only update itself")
        body = await body_json(request)
        token = body.get("fcmToken")
        await run_in_threadpool(devices.set_fcm_token, device_id, token or None)
        return {"accepted": True}

    @app.delete("/api/v1/devices/{device_id}")
    async def unpair(device_id: str, request: Request):
        device = await device_dep(request)
        if device_id != device["deviceId"]:
            raise ForbiddenError("a device can only remove itself")
        await run_in_threadpool(devices.revoke, device_id)
        bus.close_owner(device_id)
        audit_log(device, "unpair")
        return {"accepted": True}

    @app.post("/api/v1/push/test")
    async def push_test(request: Request):
        device = await device_dep(request)
        st = await run_in_threadpool(push.status)
        if not st["available"]:
            raise UnavailableError(st["reason"] or "push unavailable", code="push_unavailable")
        payload = hub._payload("completed", None, "agentdeck-test", "Agent Deck", "Encrypted test notification", None, None)
        result = await run_in_threadpool(push.deliver, payload, device["deviceId"])
        return {"result": result}

    # ---------------------------------------------------------- notifications
    @app.get("/api/v1/notifications")
    async def recent_notifications(request: Request, limit: int = 50):
        await device_dep(request)
        items = list(hub.notifications)[-max(1, min(int(limit), 200)):]
        return {"notifications": items}

    @app.post("/api/v1/notify")
    async def notify(request: Request):
        check_local_token(bearer(request), local_token)
        body = await body_json(request)
        sid = check_id(body.get("sessionId"), SESSION_ID_RE, "sessionId")
        kind = body.get("kind")
        if kind not in cfgmod.NOTIFY_KINDS:
            raise InvalidInputError(f"kind must be one of {cfgmod.NOTIFY_KINDS}")
        title = body.get("title")
        text = body.get("body", "")
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise InvalidInputError("title required (max 200 chars)")
        if not isinstance(text, str) or len(text) > 4000:
            raise InvalidInputError("body must be a string (max 4000 chars)")
        progress = body.get("progress")
        if progress is not None and clean_progress(progress) is None:
            raise InvalidInputError("progress needs 0 <= current <= total and total > 0")
        stage = body.get("stage")
        if stage is not None and (not isinstance(stage, str) or len(stage) > 200):
            raise InvalidInputError("stage must be a short string")
        payload = await run_in_threadpool(hub.agent_notify, sid, title, text, kind, progress, stage)
        audit_log(None, "notify", sessionId=payload["sessionId"], kind=kind)
        return {"accepted": True, "eventId": payload["eventId"]}

    # -------------------------------------------------------------------- SSE
    @app.get("/api/v1/events")
    async def events(request: Request):
        device = await device_dep(request)
        token = request.state.auth_token  # bearer or web cookie, whichever authenticated
        bus.bind_loop(asyncio.get_running_loop())
        # Replacing this device's own stale streams happens before the global cap check.
        queue = bus.subscribe(device["deviceId"], MAX_STREAMS_PER_DEVICE)
        if bus.subscriber_count > scfg.max_sse_clients:
            bus.unsubscribe(queue)
            raise AgentDeckError("too many event streams", code="too_many_streams", status=503)

        def still_paired() -> bool:
            try:
                devices.authenticate(token)
                return True
            except AuthError:
                return False

        async def stream():
            try:
                yield "retry: 3000\n\n"
                yield format_sse(json.dumps({"type": "sessions"}))
                while True:
                    keepalive = False
                    try:
                        msg = await asyncio.wait_for(queue.get(), timeout=15)
                    except asyncio.TimeoutError:
                        if await request.is_disconnected():
                            break
                        msg, keepalive = "", True
                    # Revocation (app or admin CLI) ends the stream before anything else is sent.
                    if msg is CLOSE or not await run_in_threadpool(still_paired):
                        break
                    yield ": keepalive\n\n" if keepalive else format_sse(msg)
            finally:
                bus.unsubscribe(queue)

        return StreamingResponse(
            stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    # ------------------------------------------------------------ web dashboard
    web.register(
        app,
        web.WebDeps(
            devices=devices,
            apk_dir=scfg.resolved_apk_dir,
            read_json_body=body_json,
            authenticate=device_dep,
            on_revoke=bus.close_owner,
            audit=audit_log,
            server_name=scfg_server_name,
        ),
    )

    # -------------------------------------------------------------- downloads
    @app.get("/download/{token}", response_class=HTMLResponse)
    async def download_page(token: str):
        download_limiter.check()
        if not downloads.valid(token):
            raise NotFoundError("link expired")
        apks = await run_in_threadpool(find_apks, scfg.resolved_apk_dir())
        rows = "".join(
            f'<li><a href="/download/{html.escape(token)}/{html.escape(a.name)}">{html.escape(a.name)}</a>'
            f"<div class=m>{a.size // 1024} KB · {html.escape(a.modified)} · SHA-256 <code>{a.sha256}</code></div></li>"
            for a in apks
        ) or "<li>No APK has been built yet.</li>"
        page = f"""<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><meta name=robots content=noindex>
<title>Agent Deck download</title><style>
body{{font:16px system-ui,sans-serif;margin:0;padding:24px;max-width:640px;color:#1b1b1f;background:#fff}}
@media (prefers-color-scheme:dark){{body{{color:#e4e4e8;background:#121214}}a{{color:#9ab8ff}}}}
h1{{font-size:20px}}ul{{padding:0;list-style:none}}li{{padding:12px 0;border-bottom:1px solid #8884}}
.m{{font-size:13px;opacity:.75;word-break:break-all;margin-top:4px}}</style></head><body>
<h1>Agent Deck for Android</h1><ul>{rows}</ul>
<p class=m>Private link, expires automatically. After installing, pair from the Mac with <code>agentdeck pair</code>.</p>
</body></html>"""
        return HTMLResponse(page)

    @app.get("/download/{token}/{filename}")
    async def download_file(token: str, filename: str):
        download_limiter.check()
        if not downloads.valid(token):
            raise NotFoundError("link expired")
        apks = await run_in_threadpool(find_apks, scfg.resolved_apk_dir(), False)
        match = next((a for a in apks if a.name == filename), None)
        if match is None:
            raise NotFoundError("file not found")
        return FileResponse(
            match.path,
            media_type="application/vnd.android.package-archive",
            filename=match.name,
            headers={"Cache-Control": "no-store"},
        )

    return app


def _host_only(header: str) -> str:
    if header.startswith("["):
        return header.split("]", 1)[0] + "]"
    return header.rsplit(":", 1)[0] if header.count(":") == 1 else header
