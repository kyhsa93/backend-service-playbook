package middleware

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"golang.org/x/time/rate"
)

// rateLimitErrorResponse는 root docs/architecture/error-handling.md가 요구하는
// {statusCode, code, message, error} 표준 JSON 에러 응답과 같은 필드 구성을 갖는다.
// interface/http 패키지의 ErrorResponse를 그대로 재사용하지 못하는 이유는, middleware
// 패키지가 그 패키지를 import하면 router.go(interface/http)가 middleware를 import하는
// 것과 순환 참조가 되기 때문이다 — 스키마만 동일하게 복제해 둔다.
type rateLimitErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// RateLimit은 전체 서버에 걸쳐 초당 요청 수를 제한하는 토큰 버킷 미들웨어다.
// limiter 하나를 모든 요청이 공유하므로, 특정 클라이언트가 아니라 서버 전체의 처리량을 제한한다.
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
