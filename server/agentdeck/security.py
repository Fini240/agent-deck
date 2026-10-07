"""Input validation and output redaction helpers."""

from __future__ import annotations

import re

from .errors import InvalidInputError

SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_.:@-]{1,160}$")
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{8,128}$")
APPROVAL_ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,160}$")
# C0 controls except TAB and LF, DEL, and C1 controls. ESC is the important one:
# it could end a bracketed paste ("\x1b[201~") and turn text into keystrokes.
_FORBIDDEN_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f\x80-\x9f]")
MAX_SEND_CHARS = 32_000
MAX_PREVIEW_CHARS = 64_000

_SECRET_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"sk-ant-[A-Za-z0-9_\-]{10,}"), "sk-ant-[REDACTED]"),
    (re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_\-]{20,}"), "sk-[REDACTED]"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"), "gh_[REDACTED]"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"), "github_pat_[REDACTED]"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"), "xox-[REDACTED]"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AKIA[REDACTED]"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"), "AIza[REDACTED]"),
    (re.compile(r"\badk_[A-Za-z0-9_\-]{20,}"), "adk_[REDACTED]"),
    (re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"), "[REDACTED_JWT]"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\Z)"), "[REDACTED_PRIVATE_KEY]"),
    (re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-~+/=]{16,}"), r"\1[REDACTED]"),
    (
        re.compile(r"(?i)\b([A-Z0-9_]*(?:api[_-]?key|secret|token|password|passwd)[A-Z0-9_]*\s*[=:]\s*)(['\"]?)[^\s'\"]{8,}\2"),
        r"\1\2[REDACTED]\2",
    ),
]


def redact(text: str) -> str:
    if not text:
        return text
    for pattern, repl in _SECRET_PATTERNS:
        text = pattern.sub(repl, text)
    return text


def bounded_preview(text: str, max_chars: int = MAX_PREVIEW_CHARS) -> str:
    text = text or ""
    if len(text) > max_chars:
        text = text[-max_chars:]
        nl = text.find("\n")
        if 0 <= nl < 200:
            text = text[nl + 1 :]
    return redact(text)


def clean_send_text(text: object) -> str:
    if not isinstance(text, str):
        raise InvalidInputError("text must be a string")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if not text.strip():
        raise InvalidInputError("text is empty")
    if len(text) > MAX_SEND_CHARS:
        raise InvalidInputError(f"text longer than {MAX_SEND_CHARS} characters")
    if _FORBIDDEN_CONTROL.search(text):
        raise InvalidInputError("text contains terminal control characters")
    return text


def check_id(value: object, pattern: re.Pattern[str], what: str) -> str:
    if not isinstance(value, str) or not pattern.match(value):
        raise InvalidInputError(f"invalid {what}")
    return value
