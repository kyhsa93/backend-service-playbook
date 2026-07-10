from __future__ import annotations

import uuid
from contextvars import ContextVar

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"


def set_correlation_id(value: str) -> None:
    _correlation_id.set(value)


def generate_correlation_id() -> str:
    return uuid.uuid4().hex
