package middleware

import "net/http"

// Defined but never called anywhere — a violation case (dead code).
func RateLimit(limit int) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return next
	}
}
