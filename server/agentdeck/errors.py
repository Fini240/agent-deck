"""Shared exception types.

Provider and terminal code may raise these; the HTTP layer maps them to
``{"error": {"code", "message"}}`` responses. Messages must be safe to show on
the phone (no secrets, no raw tracebacks).
"""

from __future__ import annotations


class AgentDeckError(Exception):
    status = 500
    code = "internal_error"

    def __init__(self, message: str = "", *, code: str | None = None, status: int | None = None):
        super().__init__(message or self.__class__.__name__)
        self.message = message or self.code.replace("_", " ")
        if code:
            self.code = code
        if status:
            self.status = status


class InvalidInputError(AgentDeckError):
    status = 400
    code = "invalid_input"


class AuthError(AgentDeckError):
    status = 401
    code = "unauthorized"


class ForbiddenError(AgentDeckError):
    status = 403
    code = "forbidden"


class NotFoundError(AgentDeckError):
    status = 404
    code = "not_found"


class ConflictError(AgentDeckError):
    status = 409
    code = "conflict"


class NotManagedError(ConflictError):
    """Session is discovered read-only and cannot be controlled yet."""

    code = "not_managed"


class StaleApprovalError(ConflictError):
    """The approval prompt changed or vanished since it was listed."""

    code = "stale_approval"


class NotReadyError(ConflictError):
    """Terminal did not reach a safe input-ready state in time."""

    code = "not_ready"


class DeliveryUncertainError(ConflictError):
    """Keys were already sent but the terminal did not confirm the result.

    The action may have taken effect, so a retry with the same request ID must not
    repeat it; ``IdempotencyStore`` caches errors carrying ``uncertain = True``.
    """

    code = "delivery_uncertain"
    uncertain = True


class RateLimitedError(AgentDeckError):
    status = 429
    code = "rate_limited"


class UnsupportedError(AgentDeckError):
    status = 501
    code = "unsupported"


class UnavailableError(AgentDeckError):
    status = 503
    code = "unavailable"
