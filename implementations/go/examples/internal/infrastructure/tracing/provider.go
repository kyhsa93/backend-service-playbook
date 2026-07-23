// Package tracing holds an infrastructure accessory (an OpenTelemetry
// TracerProvider) that gives every HTTP request a trace, following
// observability.md's directional note ("OpenTelemetry auto-instrumentation to
// automatically collect HTTP ... spans"). It doesn't belong to any specific
// domain and is used only once, at startup in main.go — the same role
// infrastructure/logging plays for the slog handler.
package tracing

import (
	"context"
	"fmt"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// serviceName is reported on every span's resource attributes so a trace
// backend can tell which service emitted it — this app doesn't run more than
// one service, so a constant (rather than a config-derived value) is enough.
const serviceName = "account-service"

// NewTracerProvider builds an *sdktrace.TracerProvider and registers it (plus
// a W3C tracecontext propagator) as the process-wide default via
// otel.SetTracerProvider/otel.SetTextMapPropagator — from then on,
// otelhttp.NewHandler (router.go) and every otel.Tracer(...) call anywhere in
// the app (outbox/consumer.go) automatically use it.
//
// otlpEndpoint mirrors config.LoadJWTSecret's "sane dev default, real value
// required in prod" shape: empty (the local-dev default — no
// OTEL_EXPORTER_OTLP_ENDPOINT set) exports spans to stdout, so `docker
// compose up` / `go run` never needs a real collector. A non-empty value
// switches to a real OTLP/HTTP exporter pointed at that collector (a Jaeger/
// Tempo/Datadog-agent OTLP endpoint in staging/production).
//
// The returned shutdown func flushes any buffered spans and must be called
// during graceful shutdown (main.go), after the HTTP server stops accepting
// new requests but before the process exits.
func NewTracerProvider(ctx context.Context, otlpEndpoint string) (shutdown func(context.Context) error, err error) {
	exporter, err := newExporter(ctx, otlpEndpoint)
	if err != nil {
		return nil, fmt.Errorf("build span exporter: %w", err)
	}

	res, err := resource.Merge(resource.Default(), resource.NewSchemaless(semconv.ServiceName(serviceName)))
	if err != nil {
		return nil, fmt.Errorf("build resource: %w", err)
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)

	otel.SetTracerProvider(provider)
	// TraceContext is the W3C "traceparent" header format
	// (docs/architecture/observability.md's "including traceparent in the
	// outbox payload") — otelhttp reads/writes it via this same
	// process-wide propagator, and internal/infrastructure/outbox reuses it
	// to carry the trace across the async SQS hop.
	otel.SetTextMapPropagator(propagation.TraceContext{})

	return provider.Shutdown, nil
}

func newExporter(ctx context.Context, otlpEndpoint string) (sdktrace.SpanExporter, error) {
	if otlpEndpoint == "" {
		return stdouttrace.New(stdouttrace.WithPrettyPrint())
	}
	return otlptracehttp.New(ctx, otlptracehttp.WithEndpoint(otlpEndpoint))
}
