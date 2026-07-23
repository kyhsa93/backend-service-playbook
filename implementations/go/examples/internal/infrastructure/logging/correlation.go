// Package logging holds an infrastructure accessory (a custom Handler) that
// wraps log/slog. It doesn't belong to any specific domain and is used only
// once, at logger initialization in main.go.
package logging

import (
	"context"
	"log/slog"

	"go.opentelemetry.io/otel/trace"

	"github.com/example/account-service/internal/common"
)

// CorrelationHandler wraps a slog.Handler and automatically adds a
// "correlation_id" field to every log record whenever ctx carries a
// correlation ID. Using this handler means each individual log call site
// (slog.InfoContext(ctx, ...), etc.) doesn't need to pass
// "correlation_id", middleware.CorrelationIDFromContext(ctx) every time
// (see observability.md).
//
// It also adds a "trace_id" field whenever ctx carries an active OpenTelemetry
// span — otelhttp.NewHandler (router.go) starts that span for every incoming
// request, and outbox.Consumer starts one for every async event it processes,
// so this one handler covers both without a second, parallel mechanism
// ("Including trace_id in log records lets you jump from a trace to its
// logs," observability.md).
type CorrelationHandler struct {
	slog.Handler
}

// NewCorrelationHandler creates a CorrelationHandler that wraps next.
func NewCorrelationHandler(next slog.Handler) *CorrelationHandler {
	return &CorrelationHandler{Handler: next}
}

// Handle adds the fields to the record if ctx carries them, then delegates to the inner Handler.
func (h *CorrelationHandler) Handle(ctx context.Context, record slog.Record) error {
	if correlationID := common.CorrelationIDFromContext(ctx); correlationID != "" {
		record.AddAttrs(slog.String("correlation_id", correlationID))
	}
	if spanContext := trace.SpanContextFromContext(ctx); spanContext.IsValid() {
		record.AddAttrs(slog.String("trace_id", spanContext.TraceID().String()))
	}
	return h.Handler.Handle(ctx, record)
}

// WithAttrs/WithGroup must also re-wrap the new Handler returned by the
// wrapped Handler in a CorrelationHandler — otherwise a child logger
// created via slog.Logger.With(...)/WithGroup(...) loses the automatic
// correlation_id injection.
func (h *CorrelationHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithAttrs(attrs)}
}

func (h *CorrelationHandler) WithGroup(name string) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithGroup(name)}
}
