package middleware

import (
	"context"
	"net/http"
	"strings"
)

type contextKey string

const userIDKey contextKey = "userID"

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
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(authorization, "Bearer ")
			userID, err := verifier.Verify(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
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
