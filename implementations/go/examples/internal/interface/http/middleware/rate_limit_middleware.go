package middleware

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"golang.org/x/time/rate"
)

// rateLimitErrorResponse has the same field layout as the standard
// {statusCode, code, message, error} JSON error response required by root
// docs/architecture/error-handling.md. It cannot simply reuse the
// interface/http package's ErrorResponse because, if the middleware package
// imported that package, it would form a circular reference with
// router.go (interface/http) importing middleware — so only the schema is
// duplicated here.
type rateLimitErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// RateLimit is a token-bucket middleware that limits requests per second
// across the entire server. Since all requests share a single limiter, it
// throttles the server's overall throughput rather than any specific client.
func RateLimit(limiter *rate.Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !limiter.Allow() {
				w.Header().Set("Retry-After", "1")
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusTooManyRequests)
				body := rateLimitErrorResponse{
					StatusCode: http.StatusTooManyRequests,
					Code:       "RATE_LIMIT_EXCEEDED",
					Message:    "too many requests",
					Error:      http.StatusText(http.StatusTooManyRequests),
				}
				if err := json.NewEncoder(w).Encode(body); err != nil {
					slog.ErrorContext(r.Context(), "failed to encode rate limit error response", "error", err)
				}
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
