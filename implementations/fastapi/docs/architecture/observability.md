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

## Metrics — `GET /metrics` via `prometheus-fastapi-instrumentator`

`main.py` wires up the request-count/duration metrics right after every router is registered:

```python
# main.py — actual code
from prometheus_fastapi_instrumentator import Instrumentator, metrics

Instrumentator().add(metrics.requests()).add(metrics.latency()).instrument(app).expose(
    app, endpoint="/metrics", include_in_schema=False
)
```

This exposes `http_requests_total` (a counter) and `http_request_duration_seconds` (a histogram), both labeled by `method`/`handler`/`status` — exactly the "request count + duration histogram" combination `docs/architecture/observability.md` (root) asks for. `include_in_schema=False` keeps this purely operational endpoint out of the Swagger doc. Verified for real: `curl http://localhost:8000/metrics` after a couple of requests returns lines like `http_requests_total{handler="/health/live",method="GET",status="2xx"} 2.0` in Prometheus text-exposition format — no scrape config is required for the endpoint itself to work.

---

## Tracing — OpenTelemetry auto-instrumentation + Outbox propagation

`src/common/tracing.py`'s `configure_tracing(app)` is called once at startup (`main.py`, right after routers are registered) and does two things: builds a global `TracerProvider`, and calls `FastAPIInstrumentor.instrument_app(app)` so every route gets a span automatically — no manual `tracer.start_span()` calls needed in route functions.

```python
# src/common/tracing.py — actual code (abbreviated)
def configure_tracing(app: FastAPI) -> None:
    config = TracingConfig()  # type: ignore[call-arg]
    resource = Resource.create({SERVICE_NAME: config.service_name})
    provider = TracerProvider(resource=resource)
    if config.exporter_otlp_endpoint:
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=config.exporter_otlp_endpoint)))
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app)
```

**Sane dev default, real value required in production** — the same split `config.md` already documents for `JWT_SECRET`/`AwsConfig`. `TracingConfig` (`src/config/tracing_config.py`) reads `OTEL_EXPORTER_OTLP_ENDPOINT`; when it's unset (the local/test default — nothing in `.env.example`/`conftest.py` sets it), no `SpanProcessor` is attached at all, so spans are still created (trace_id/traceparent keep flowing) but exported nowhere — no collector needs to be running for the app, or the test suite, to work. Set the env var to a real OTLP/HTTP collector endpoint (e.g. `http://otel-collector:4318`) in staging/production to actually ship spans.

### `trace_id` in every log record

`src/common/correlation.py`'s `get_trace_id()` reads the current span's trace_id (formatted the same way as in a W3C `traceparent` — 32-char lowercase hex), and `JsonFormatter` (`logging_config.py`) includes it whenever one is active:

```python
# src/common/correlation.py — actual code
def get_trace_id() -> str | None:
    span_context = trace.get_current_span().get_span_context()
    if not span_context.is_valid:
        return None
    return format(span_context.trace_id, "032x")
```

A request handled through `FastAPIInstrumentor` always has an active span, so its log lines carry `trace_id` — verified for real: `{"level": "info", "message": "GET /health/live", ..., "trace_id": "14a21a20b2e2ee2826be099326f8817d", ...}`. A background task tick with nothing currently traced (e.g. an `OutboxPoller` failure with no span open) simply omits the field.

### Carrying `traceparent` across the Outbox's async boundary

The Outbox already carries `eventType`/`event_id` (an at-least-once dedup key) across the DB → SQS → handler hop (see [domain-events.md](domain-events.md)) — `traceparent` rides along the same way, added as a third field, so an HTTP request and the async event processing it produces land in **one trace**, not two disconnected ones:

1. **`OutboxWriter.save_all()`** (`src/outbox/outbox_writer.py`) captures whatever span is active right now (the HTTP request that ran the Command, in practice) via `opentelemetry.propagate.inject()` and stores it in a new `trace_parent` column on the `outbox` table (nullable — a row written outside any active span just carries no trace link).
2. **`OutboxPoller`** (`src/outbox/outbox_poller.py`) forwards `row.trace_parent`, when present, as an SQS `MessageAttributes["traceparent"]` entry — exactly parallel to how it already forwards `eventType`.
3. **`OutboxConsumer`** (`src/outbox/outbox_consumer.py`) requests the `traceparent` attribute in `receive_message()`, re-hydrates it via `opentelemetry.propagate.extract()`, and processes the event inside a child span (`outbox.process_event`) of that re-hydrated context — falling back to a new, unlinked trace when no `traceparent` is present (no OTLP configured when the row was written, or a row written outside any request).

```python
# src/outbox/outbox_consumer.py — actual code (abbreviated)
context = extract({"traceparent": trace_parent}) if trace_parent else None
with tracer.start_as_current_span("outbox.process_event", context=context):
    ...
    await handler(payload)
```

Verified end-to-end with an e2e test (`tests/test_notification_e2e.py::test_traceparent_propagates_from_http_request_through_outbox_to_event_processing`): it attaches an `InMemorySpanExporter` to the real `TracerProvider` `configure_tracing()` already set up, sends `POST /accounts`, waits for `OutboxConsumer` to process the resulting `AccountCreated` event, and asserts the root span FastAPIInstrumentor created for the HTTP request and the `outbox.process_event` span share the same `trace_id` — not just that a string got copied into a column.

**Migration**: `migrations/versions/c1d9a3f7e2b4_add_trace_parent_to_outbox.py` adds the nullable `trace_parent` column to `outbox`.

---

## Principles

- **No logging in the Domain layer**.
- **Use structured logs**: JSON + snake_case field names. Use `extra=` instead of `%s` string interpolation.
- **Errors must always be logged before being propagated**: where an exception is swallowed after `logger.exception()` (`notify()`), this is compensated for by the Outbox in [domain-events.md](domain-events.md). The harness's `no-silent-except` rule catches the `except ...: pass` pattern (silently swallowing without logging or re-raising) in `application/`/`infrastructure/`.
- **Trace requests with a Correlation ID**: `contextvars`-based, automatically included on every log line.
- **Disable DEBUG in production**: configure log level per environment.
- **No collector required for local dev/tests**: metrics/tracing must degrade gracefully to "collected but not shipped" when unconfigured — never a hard dependency at startup.
- **Carry trace context across every async boundary, not only within a request**: `traceparent` rides the Outbox the same way `eventType`/`event_id` already do (domain-events.md), so async event processing isn't a separate, disconnected trace.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the Correlation ID injection middleware and the security-headers middleware
- [layer-architecture.md](layer-architecture.md) — separation of responsibilities per layer
- [domain-events.md](domain-events.md) — the Outbox pattern `traceparent` propagation rides on
- [config.md](config.md) — the sane-dev-default / real-value-required-in-production split `TracingConfig` follows
- [config.md](config.md) — per-environment log level configuration
