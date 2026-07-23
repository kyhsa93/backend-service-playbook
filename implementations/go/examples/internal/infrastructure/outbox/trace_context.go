package outbox

import (
	"context"
	"database/sql"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

// traceParentFromContext extracts ctx's current span as a W3C traceparent
// header string (e.g. "00-<32 hex trace id>-<16 hex span id>-<2 hex
// flags>") via the process-wide propagator that
// infrastructure/tracing.NewTracerProvider registers at startup. Writer/
// Publisher call this when inserting an outbox row, so the row carries the
// same trace as the HTTP request (or, for a row written while reacting to
// another event, the same trace as that earlier event) that produced it —
// observability.md's "including traceparent in the outbox payload ... links
// the HTTP request → event processing into a single trace."
//
// Returns an invalid (Valid: false) sql.NullString when ctx carries no
// active span (e.g. a Task Queue batch job with no request to trace), so the
// column stays NULL rather than storing an empty string.
func traceParentFromContext(ctx context.Context) sql.NullString {
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	traceParent := carrier.Get("traceparent")
	return sql.NullString{String: traceParent, Valid: traceParent != ""}
}

// contextWithTraceParent is traceParentFromContext's inverse — Consumer
// calls it after reading the "traceparent" SQS message attribute Poller
// forwarded, re-hydrating the original span context into ctx before invoking
// the registered Handler, so any span/log it produces links back into the
// same trace.
func contextWithTraceParent(ctx context.Context, traceParent string) context.Context {
	if traceParent == "" {
		return ctx
	}
	return otel.GetTextMapPropagator().Extract(ctx, propagation.MapCarrier{"traceparent": traceParent})
}
