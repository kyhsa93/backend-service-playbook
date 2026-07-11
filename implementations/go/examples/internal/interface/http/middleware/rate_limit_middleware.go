package middleware

import (
	"net/http"

	"golang.org/x/time/rate"
)

// RateLimit은 전체 서버에 걸쳐 초당 요청 수를 제한하는 토큰 버킷 미들웨어다.
// limiter 하나를 모든 요청이 공유하므로, 특정 클라이언트가 아니라 서버 전체의 처리량을 제한한다.
func RateLimit(limiter *rate.Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !limiter.Allow() {
				w.Header().Set("Retry-After", "1")
				http.Error(w, "too many requests", http.StatusTooManyRequests)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
