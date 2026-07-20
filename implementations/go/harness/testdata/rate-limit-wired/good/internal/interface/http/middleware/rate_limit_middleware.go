package middleware

import "net/http"

func RateLimit(limit int) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return next
	}
}
