package middleware

import (
	"context"
	"net/http"
	"strings"
)

type contextKey string

const userIDKey contextKey = "userID"

// TokenVerifier는 이 미들웨어가 인증 토큰 검증을 위해 필요로 하는 최소 포트다.
// internal/infrastructure/auth.JWTService의 Verify(token string) (string, error)가
// 이미 이 시그니처를 구조적으로 만족하므로, 인터페이스만 여기(사용하는 곳 근처)에
// 선언하고 구현체 import는 router.go(합성 지점)로 미룬다 — interface/ 레이어가
// infrastructure/를 직접 import하지 않게 하기 위함이다(layer-architecture.md,
// TokenIssuer/PasswordHasher와 동일한 패턴).
type TokenVerifier interface {
	Verify(token string) (string, error)
}

// RequireAuth는 Authorization: Bearer 헤더를 검증하고, 통과하면 context에
// userID를 주입한 뒤 next를 호출한다. 검증 실패 시 401을 반환하고 next를 호출하지 않는다.
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

// UserIDFromContext는 RequireAuth를 통과한 요청의 context에서 userID를 꺼낸다.
func UserIDFromContext(ctx context.Context) (string, bool) {
	userID, ok := ctx.Value(userIDKey).(string)
	return userID, ok
}
