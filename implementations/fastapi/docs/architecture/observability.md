# Observability — Logging, Metrics, Tracing

> Framework-agnostic principles: [../../../../docs/architecture/observability.md](../../../../docs/architecture/observability.md)

## Structured logging and Correlation ID

`JsonFormatter`/`configure_logging()` in `src/common/logging_config.py`, the `contextvars`-based Correlation ID in `src/common/correlation.py`, and `main.py`'s `correlation_id_middleware` all exist as actual code. `infrastructure/notification/notification_service.py` passes structured fields (`event_type`, `account_id`, `recipient`, `ses_message_id`) via `extra={...}`.

```python
# infrastructure/notification/notification_service.py — actual code
logger.info(
    "Notification email sent",
    extra={
        "event_type": event_type,
        "account_id": event.account_id,
        "recipient": recipient,
        "ses_message_id": ses_message_id,
    },
)
```

Below are the details of this actual implementation.

---

## Choice of structured logging: stdlib `logging` + a JSON formatter

`structlog` is powerful, but it adds a separate dependency and a learning cost. This project already uses the standard `logging` module (`getLogger(__name__)`) and doesn't produce a large volume of logs, so **layering a custom JSON `Formatter` on top of stdlib `logging`** is recommended — with no new dependency, it's enough to just enforce the field-naming convention (snake_case) and Correlation ID. If log volume grows significantly or context binding (`logger.bind(...)`) becomes necessary, switching to `structlog` can be reconsidered at that point.

```python
# src/common/logging_config.py — actual code
import json
import logging

from .correlation import get_correlation_id

_BASE_RECORD_KEYS = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "level": record.levelname.lower(),
            "message": record.getMessage(),
            "logger": record.name,
            "correlation_id": get_correlation_id(),
        }
        # merge in fields passed via extra={...} (e.g. account_id, duration_ms)
        for key, value in record.__dict__.items():
            if key not in _BASE_RECORD_KEYS and key != "message":
                payload[key] = value
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=level, handlers=[handler])
```

```python
# main.py — called before entering lifespan
from src.common.logging_config import configure_logging

configure_logging()
```

### Field naming — snake_case, structured fields passed via `extra=`

```python
# infrastructure/notification/notification_service.py — actual code
logger.info(
    "Notification email sent",
    extra={
        "event_type": event_type,
        "account_id": event.account_id,
        "recipient": recipient,
        "ses_message_id": ses_message_id,
    },
)
```

Instead of `%s` string interpolation, putting structured fields into `extra=` lets `JsonFormatter` merge them in as top-level JSON keys — Datadog/CloudWatch can then filter directly on `account_id`.

---

## Correlation ID — `contextvars`

The Python standard library counterpart to Node's `AsyncLocalStorage` is `contextvars`. The implementation and its middleware wiring are covered in [cross-cutting-concerns.md](cross-cutting-concerns.md) — this document only summarizes its use in logging.

```python
# src/common/correlation.py
from contextvars import ContextVar

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"
```

Since `JsonFormatter` calls `get_correlation_id()` on every log line, the Correlation ID the middleware sets when a request comes in is automatically included in every log produced while handling that request (router, Handler, `SesNotificationService.notify()`) — there's no need to pass `correlation_id` as an argument on every call.

---

## Log level policy

| Level | Purpose | Example in this repository |
|------|------|----------------|
| `ERROR` | Request-handling failure, external system outage | `logger.exception(...)` in `SesNotificationService.notify()` |
| `WARNING` | Normal operation but needs attention | (no usage yet) — e.g. a retry occurring |
| `INFO` | Key business events, startup/shutdown | Successful notification send, `lifespan` startup/shutdown |
| `DEBUG` | Development/debugging detail | SQLAlchemy query parameters |

In production, `DEBUG` is turned off via `configure_logging(level="INFO")`. In development, output goes up through `DEBUG` — this ties into the per-environment configuration in [config.md](config.md).

---

## Logging criteria per layer

| Layer | What gets logged |
|--------|----------|
| Interface (`account_router.py`, middleware) | HTTP request/response, processing time |
| Application (`*_handler.py`) | Business events (as needed) |
| Infrastructure (`notification_service.py`) | External integration failure/success, SQL performance anomalies |
| Domain (`account.py`) | **Never logged** |

`src/account/domain/account.py` imports no logger at all — Domain purity is preserved. Whether `domain/` imports `logging`/`structlog` is checked by the harness's `domain-purity` rule (logging libraries are included in the blocklist alongside fastapi/sqlalchemy/aioboto3).

---

## Metrics and tracing (directional notes)

This repository doesn't mandate a specific stack, but recommends the following for production adoption.

- **Metrics**: auto-expose `GET /metrics` with `prometheus-fastapi-instrumentator`. Key alerts: HTTP 5xx rate, p99 response time, DB connection-pool saturation.
- **Tracing**: auto-collect HTTP/DB spans with `opentelemetry-instrumentation-fastapi` + `opentelemetry-instrumentation-sqlalchemy`. Including `trace_id` in log records enables jumping between a trace and its logs.

---

## Principles

- **No logging in the Domain layer**.
- **Use structured logs**: JSON + snake_case field names. Use `extra=` instead of `%s` string interpolation.
- **Errors must always be logged before being propagated**: where an exception is swallowed after `logger.exception()` (`notify()`), this is compensated for by the Outbox in [domain-events.md](domain-events.md). The harness's `no-silent-except` rule catches the `except ...: pass` pattern (silently swallowing without logging or re-raising) in `application/`/`infrastructure/`.
- **Trace requests with a Correlation ID**: `contextvars`-based, automatically included on every log line.
- **Disable DEBUG in production**: configure log level per environment.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the Correlation ID injection middleware
- [layer-architecture.md](layer-architecture.md) — separation of responsibilities per layer
- [config.md](config.md) — per-environment log level configuration
