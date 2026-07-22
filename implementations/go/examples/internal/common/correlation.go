package common

import "context"

type correlationIDKey struct{}

// WithCorrelationID returns a new context carrying correlationID.
// interface/http/middleware.CorrelationID builds the context with this
// function on every request.
func WithCorrelationID(ctx context.Context, correlationID string) context.Context {
	return context.WithValue(ctx, correlationIDKey{}, correlationID)
}

// CorrelationIDFromContext retrieves the correlation ID carried by
// WithCorrelationID. Returns an empty string if absent.
func CorrelationIDFromContext(ctx context.Context) string {
	if id, ok := ctx.Value(correlationIDKey{}).(string); ok {
		return id
	}
	return ""
}
