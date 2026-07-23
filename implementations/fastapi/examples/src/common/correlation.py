from __future__ import annotations

import uuid
from contextvars import ContextVar

from opentelemetry import trace

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"


def set_correlation_id(value: str) -> None:
    _correlation_id.set(value)


def generate_correlation_id() -> str:
    return uuid.uuid4().hex


def get_trace_id() -> str | None:
    """Returns the current OpenTelemetry trace_id (as a 32-char lowercase hex string, the
    same format used in a W3C `traceparent` header) so `JsonFormatter` can fold it into every
    log record — see observability.md ("Including trace_id in log records lets you jump from
    a trace to its logs"). Returns None outside any active span (e.g. a background-task tick
    with nothing currently traced), which `JsonFormatter` treats as "omit the field".
    """
    span_context = trace.get_current_span().get_span_context()
    if not span_context.is_valid:
        return None
    return format(span_context.trace_id, "032x")
