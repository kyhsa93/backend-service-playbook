package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"

	"github.com/example/account-service/internal/common"
)

// CorrelationID reads the X-Correlation-Id header, carries it in the
// context, and also echoes it back as a response header. If the header is
// absent, it issues a new one — this value is used to trace a single request
// across multiple services in a distributed environment (see
// observability.md). The key that carries the value in the context lives in
// internal/common (a framework-independent package any domain/layer can
// reference), so that infrastructure/logging.CorrelationHandler can read the
// same value too.
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
		}
		ctx := common.WithCorrelationID(r.Context(), correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// CorrelationIDFromContext retrieves the correlation ID from the context of a
// request that has passed the CorrelationID middleware. Returns an empty
// string if absent. Delegates to common.CorrelationIDFromContext — this
// function is kept for backward compatibility.
func CorrelationIDFromContext(ctx context.Context) string {
	return common.CorrelationIDFromContext(ctx)
}
