package middleware

import (
	"context"
	"net/http"
	"strings"

	"github.com/example/account-service/internal/infrastructure/auth"
)

type contextKey string

const userIDKey contextKey = "userID"

// RequireAuth는 Authorization: Bearer 헤더를 검증하고, 통과하면 context에
// userID를 주입한 뒤 next를 호출한다. 검증 실패 시 401을 반환하고 next를 호출하지 않는다.
func RequireAuth(jwtService *auth.JWTService) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authorization := r.Header.Get("Authorization")
			if !strings.HasPrefix(authorization, "Bearer ") {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			token := strings.TrimPrefix(authorization, "Bearer ")
			userID, err := jwtService.Verify(token)
			if err != nil {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// UserIDFromContext는 RequireAuth를 통과한 요청의 context에서 userID를 꺼낸다.
func UserIDFromContext(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(userIDKey).(string)
	return userID, ok
}
