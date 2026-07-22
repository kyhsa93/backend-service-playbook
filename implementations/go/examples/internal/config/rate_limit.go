package config

import (
	"os"
	"strconv"
)

// RateLimitConfig holds the thresholds for the global token-bucket rate
// limiter. Production values are tuned via environment variables (defaults
// are used if unset), and situations like e2e tests, which send dozens of
// requests in a short time within the same process, override them with
// generous values.
type RateLimitConfig struct {
	RequestsPerSecond float64 // average requests allowed per second
	Burst             int     // burst size allowed momentarily
}

func LoadRateLimitConfig() RateLimitConfig {
	cfg := RateLimitConfig{RequestsPerSecond: 100, Burst: 20}

	if v := os.Getenv("RATE_LIMIT_RPS"); v != "" {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			cfg.RequestsPerSecond = parsed
		}
	}
	if v := os.Getenv("RATE_LIMIT_BURST"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.Burst = parsed
		}
	}

	return cfg
}
