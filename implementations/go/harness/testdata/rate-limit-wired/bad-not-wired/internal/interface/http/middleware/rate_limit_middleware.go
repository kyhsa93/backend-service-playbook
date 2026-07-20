package middleware

import "net/http"

// 정의만 되어 있고 어디서도 호출되지 않는 위반 사례(죽은 코드).
func RateLimit(limit int) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return next
	}
}
