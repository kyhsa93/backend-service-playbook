package middleware

import "time"

func Logging() time.Duration {
	start := time.Now()
	return time.Since(start)
}
