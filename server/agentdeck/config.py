"""Configuration.

Two layers:

* ``config.json`` — local-only server configuration (ports, FCM credential path,
  workspace roots, APK directory). Never changeable over the network.
* ``settings.json`` — user preferences the phone may PATCH (validated subset).

Everything lives in ``$AGENTDECK_HOME`` (default ``~/.agent-deck``, mode 0700).
"""

from __future__ import annotations

import copy
import os
import re
import secrets
import shutil
import subprocess
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .errors import InvalidInputError
from .storage import ensure_private_dir, locked, read_json, write_private_bytes, write_private_json

DEFAULT_PORT = 18787
BIND_HOST = "127.0.0.1"
REPO_ROOT = Path(__file__).resolve().parents[2]

AGENTS = ("claude", "codex")
NOTIFY_KINDS = ("progress", "completed", "error", "input")
KEEP_AWAKE_MODES = ("active", "plugged_in", "off")
MODEL_ID_RE = re.compile(r"^[A-Za-z0-9._:/@+\[\]-]{1,128}$")


def home_dir() -> Path:
    raw = os.environ.get("AGENTDECK_HOME")
    path = Path(raw).expanduser() if raw else Path.home() / ".agent-deck"
    return ensure_private_dir(path)


def user_home() -> Path:
    return Path(os.environ.get("AGENTDECK_USER_HOME") or Path.home()).expanduser().resolve()


def local_base_url(port: int | None = None) -> str:
    return f"http://{BIND_HOST}:{port or load_server_config().port}"


# ---------------------------------------------------------------- local token

def local_token_path() -> Path:
    return home_dir() / "local-token"


def ensure_local_token() -> str:
    path = local_token_path()
    with locked(path):
        try:
            token = path.read_text().strip()
        except FileNotFoundError:
            token = ""
        if len(token) < 32:
            token = secrets.token_urlsafe(32)
            write_private_bytes(path, (token + "\n").encode())
        elif os.name == "posix":
            os.chmod(path, 0o600)
    return token


def read_local_token() -> str:
    """For local CLIs. Creates the token if the server has never run."""
    return ensure_local_token()


# --------------------------------------------------------------- server config

@dataclass
class FcmConfig:
    enabled: bool = True
    service_account_file: str = ""  # default: <home>/firebase-service-account.json
    project_id: str | None = None


@dataclass
class ServerConfig:
    port: int = DEFAULT_PORT
    public_base_url: str | None = None
    extra_allowed_hosts: list[str] = field(default_factory=list)
    workspace_roots: list[str] = field(default_factory=list)
    poll_interval_seconds: float = 3.0
    completion_debounce_seconds: float = 6.0
    notify_activity_lease_seconds: float = 600.0
    apk_dir: str = ""
    max_sse_clients: int = 8
    fcm: FcmConfig = field(default_factory=FcmConfig)

    def resolved_workspace_roots(self) -> list[Path]:
        roots = self.workspace_roots or [str(user_home())]
        return [Path(r).expanduser().resolve() for r in roots]

    def resolved_apk_dir(self) -> Path:
        return Path(self.apk_dir).expanduser() if self.apk_dir else REPO_ROOT / "outputs"

    def service_account_path(self) -> Path:
        if self.fcm.service_account_file:
            return Path(self.fcm.service_account_file).expanduser()
        return home_dir() / "firebase-service-account.json"


def config_path() -> Path:
    return home_dir() / "config.json"


def load_server_config() -> ServerConfig:
    raw = read_json(config_path(), {})
    if not isinstance(raw, dict):
        raw = {}
    fcm_raw = raw.get("fcm") if isinstance(raw.get("fcm"), dict) else {}
    cfg = ServerConfig(
        port=int(os.environ.get("AGENTDECK_PORT") or raw.get("port") or DEFAULT_PORT),
        public_base_url=(raw.get("publicBaseUrl") or None),
        extra_allowed_hosts=[str(h) for h in raw.get("extraAllowedHosts", []) if isinstance(h, str)],
        workspace_roots=[str(r) for r in raw.get("workspaceRoots", []) if isinstance(r, str)],
        poll_interval_seconds=float(raw.get("pollIntervalSeconds", 3.0)),
        completion_debounce_seconds=float(raw.get("completionDebounceSeconds", 6.0)),
        notify_activity_lease_seconds=float(raw.get("notifyActivityLeaseSeconds", 600.0)),
        apk_dir=str(raw.get("apkDir") or ""),
        max_sse_clients=int(raw.get("maxSseClients", 8)),
        fcm=FcmConfig(
            enabled=bool(fcm_raw.get("enabled", True)),
            service_account_file=str(fcm_raw.get("serviceAccountFile") or ""),
            project_id=fcm_raw.get("projectId") or None,
        ),
    )
    if cfg.public_base_url:
        cfg.public_base_url = cfg.public_base_url.rstrip("/")
    return cfg


def save_server_config_value(key: str, value: Any) -> None:
    """Local-only helper used by the admin CLI (e.g. publicBaseUrl)."""
    path = config_path()
    with locked(path):
        raw = read_json(path, {})
        if not isinstance(raw, dict):
            raw = {}
        raw[key] = value
        write_private_json(path, raw)


DEFAULT_SERVE_PORT = 10443  # 8443 is taken by another app on this Mac (LinkMyDroid)


def detect_tailscale_base_url(port: int | None = None) -> str | None:
    """Best effort ``https://<magicdns-name>:<port>`` from the local Tailscale CLI."""
    if port is None:
        port = int(os.environ.get("AGENTDECK_SERVE_PORT") or DEFAULT_SERVE_PORT)
    candidates = [
        shutil.which("tailscale"),
        "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
    ]
    for exe in candidates:
        if not exe or not os.path.exists(exe):
            continue
        try:
            out = subprocess.run(
                [exe, "status", "--json", "--self=true", "--peers=false"],
                capture_output=True,
                timeout=5,
                check=False,
            )
            data = json.loads(out.stdout or b"{}")
        except (OSError, subprocess.SubprocessError, ValueError):
            continue
        name = ((data.get("Self") or {}).get("DNSName") or "").rstrip(".")
        if name:
            return f"https://{name}:{port}"
    return None


# -------------------------------------------------------------------- settings

DEFAULT_SETTINGS: dict[str, Any] = {
    "allowedWorkspaces": [],  # filled with the user's home on first load
    "defaultAgent": "claude",
    "defaultModels": {},
    # progress pushes are silent; without them background activity never reaches the phone
    "notifyOn": ["progress", "completed", "error", "input"],
    "progressIntervalSeconds": 30,
    "keepAwakeMode": "active",
}

DENIED_WORKSPACE_PARTS = {".ssh", ".gnupg", ".agent-deck", "Keychains"}


def settings_path() -> Path:
    return home_dir() / "settings.json"


def load_settings() -> dict[str, Any]:
    raw = read_json(settings_path(), {})
    merged = copy.deepcopy(DEFAULT_SETTINGS)
    if isinstance(raw, dict):
        for key in DEFAULT_SETTINGS:
            if key in raw:
                merged[key] = raw[key]
    if not merged["allowedWorkspaces"]:
        merged["allowedWorkspaces"] = [str(user_home())]
    return merged


def is_denied_workspace(path: Path) -> bool:
    if path == Path(path.anchor):
        return True
    if any(part in DENIED_WORKSPACE_PARTS for part in path.parts):
        return True
    try:
        path.relative_to(home_dir().resolve())
        return True
    except ValueError:
        return False


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def validate_settings_patch(patch: dict[str, Any], current: dict[str, Any], cfg: ServerConfig) -> dict[str, Any]:
    if not isinstance(patch, dict):
        raise InvalidInputError("settings patch must be an object")
    unknown = set(patch) - set(DEFAULT_SETTINGS)
    if unknown:
        raise InvalidInputError(f"unsupported settings: {', '.join(sorted(unknown))}")
    out = copy.deepcopy(current)
    if "allowedWorkspaces" in patch:
        value = patch["allowedWorkspaces"]
        if not isinstance(value, list) or not 1 <= len(value) <= 32:
            raise InvalidInputError("allowedWorkspaces must be a list of 1-32 paths")
        roots = cfg.resolved_workspace_roots()
        clean: list[str] = []
        for item in value:
            if not isinstance(item, str) or not item.startswith(("/", "~")) or len(item) > 1024 or "\x00" in item:
                raise InvalidInputError("workspace paths must be absolute")
            p = Path(item).expanduser().resolve()
            if not p.is_dir():
                raise InvalidInputError(f"not a directory: {item}")
            if is_denied_workspace(p) or not any(_within(p, r) for r in roots):
                raise InvalidInputError(f"workspace not permitted: {item}")
            if str(p) not in clean:
                clean.append(str(p))
        out["allowedWorkspaces"] = clean
    if "defaultAgent" in patch:
        if patch["defaultAgent"] not in AGENTS:
            raise InvalidInputError("defaultAgent must be claude or codex")
        out["defaultAgent"] = patch["defaultAgent"]
    if "defaultModels" in patch:
        value = patch["defaultModels"]
        if not isinstance(value, dict) or set(value) - set(AGENTS):
            raise InvalidInputError("defaultModels keys must be claude/codex")
        models: dict[str, str] = {}
        for agent, model in value.items():
            if model in (None, ""):
                continue
            if not isinstance(model, str) or not MODEL_ID_RE.match(model):
                raise InvalidInputError(f"invalid model id for {agent}")
            models[agent] = model
        out["defaultModels"] = models
    if "notifyOn" in patch:
        value = patch["notifyOn"]
        if not isinstance(value, list) or any(v not in NOTIFY_KINDS for v in value):
            raise InvalidInputError(f"notifyOn entries must be in {NOTIFY_KINDS}")
        out["notifyOn"] = sorted(set(value), key=NOTIFY_KINDS.index)
    if "progressIntervalSeconds" in patch:
        value = patch["progressIntervalSeconds"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 5 <= value <= 3600:
            raise InvalidInputError("progressIntervalSeconds must be 5..3600")
        out["progressIntervalSeconds"] = value
    if "keepAwakeMode" in patch:
        if patch["keepAwakeMode"] not in KEEP_AWAKE_MODES:
            raise InvalidInputError(f"keepAwakeMode must be one of {KEEP_AWAKE_MODES}")
        out["keepAwakeMode"] = patch["keepAwakeMode"]
    return out


def save_settings(settings: dict[str, Any]) -> None:
    path = settings_path()
    with locked(path):
        write_private_json(path, settings)


def resolve_workspace(cwd: str, settings: dict[str, Any]) -> Path:
    """Return the real path of ``cwd`` if it is an existing allowed workspace directory."""
    if not isinstance(cwd, str) or not cwd or len(cwd) > 1024 or "\x00" in cwd:
        raise InvalidInputError("cwd is required")
    if not cwd.startswith(("/", "~")):
        raise InvalidInputError("cwd must be an absolute path")
    p = Path(cwd).expanduser().resolve()
    if not p.is_dir():
        raise InvalidInputError("cwd is not an existing directory")
    if is_denied_workspace(p):
        raise InvalidInputError("cwd is not permitted")
    allowed = [Path(a).expanduser().resolve() for a in settings.get("allowedWorkspaces", [])]
    if not any(_within(p, a) for a in allowed):
        raise InvalidInputError("cwd is outside the allowed workspaces")
    return p
