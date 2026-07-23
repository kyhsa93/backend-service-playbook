from __future__ import annotations

from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from ..config.tracing_config import TracingConfig

_tracer_provider: TracerProvider | None = None


def configure_tracing(app: FastAPI) -> None:
    """Sets a global `TracerProvider` and auto-instruments every route on `app` via
    `FastAPIInstrumentor` (basic OpenTelemetry auto-instrumentation, observability.md).

    `FastAPIInstrumentor` already extracts an incoming `traceparent` header (W3C Trace
    Context) and continues that trace instead of starting a new one — this is exactly how an
    Outbox-replayed event's `traceparent` (outbox_poller.py → SQS message attribute →
    outbox_consumer.py) gets linked back into the HTTP request that originally produced it,
    with no extra propagation code needed on the HTTP side.

    No `OTEL_EXPORTER_OTLP_ENDPOINT` configured (the local/test default; see
    `TracingConfig`) → no `SpanProcessor` is attached at all, so spans are created (trace_id
    keeps flowing into logs, traceparent keeps flowing through the Outbox) but never
    exported anywhere — no collector needs to be running for the app, or the test suite, to
    work.
    """
    global _tracer_provider
    config = TracingConfig()  # type: ignore[call-arg]
    resource = Resource.create({SERVICE_NAME: config.service_name})
    provider = TracerProvider(resource=resource)
    if config.exporter_otlp_endpoint:
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=config.exporter_otlp_endpoint)))
    trace.set_tracer_provider(provider)
    _tracer_provider = provider
    FastAPIInstrumentor.instrument_app(app)


def get_tracer(name: str) -> trace.Tracer:
    return trace.get_tracer(name)


def shutdown_tracing() -> None:
    if _tracer_provider is not None:
        _tracer_provider.shutdown()
