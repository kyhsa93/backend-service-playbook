package middleware

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
)

type contextKey string

const userIDKey contextKey = "userID"

// authErrorResponse has the same field layout as the standard
// {statusCode, code, message, error} JSON error response required by root
// docs/architecture/error-handling.md — duplicated here rather than
// importing interface/http.ErrorResponse for the same reason
// rate_limit_middleware.go duplicates its own rateLimitErrorResponse: that
// import direction would create a circular reference with router.go
// (interface/http) importing middleware.
type authErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// writeUnauthorized responds with the standard JSON error schema — every
// handler's Swagger @Failure 401 annotation documents this shape, so a
// plain-text 401 here would silently break that contract.
func writeUnauthorized(w http.ResponseWriter, r *http.Request, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	body := authErrorResponse{
		StatusCode: http.StatusUnauthorized,
		Code:       "UNAUTHORIZED",
		Message:    message,
		Error:      http.StatusText(http.StatusUnauthorized),
	}
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.ErrorContext(r.Context(), "failed to encode auth error response", "error", err)
	}
}

// TokenVerifier is the minimal port this middleware needs for auth token
// verification. internal/infrastructure/auth.JWTService's
// Verify(token string) (string, error) already structurally satisfies this
// signature, so only the interface is declared here (near its point of use),
// deferring the implementation import to router.go (the composition point)
// — this keeps the interface/ layer from importing infrastructure/ directly
// (layer-architecture.md, the same pattern as TokenIssuer/PasswordHasher).
type TokenVerifier interface {
	Verify(token string) (string, error)
}

// RequireAuth verifies the Authorization: Bearer header, and on success
// injects userID into the context before calling next. On verification
// failure, it returns 401 and never calls next.
func RequireAuth(verifier TokenVerifier) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authorization := r.Header.Get("Authorization")
			if !strings.HasPrefix(authorization, "Bearer ") {
				writeUnauthorized(w, r, "the bearer token is missing or malformed")
				return
			}
			token := strings.TrimPrefix(authorization, "Bearer ")
			userID, err := verifier.Verify(token)
			if err != nil {
				writeUnauthorized(w, r, "the bearer token is invalid")
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// UserIDFromContext retrieves userID from the context of a request that has
// passed RequireAuth.
func UserIDFromContext(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(userIDKey).(string)
	return userID, ok
}
