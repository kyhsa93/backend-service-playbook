package config

import (
	"os"
	"strconv"
)

// InterestConfig is the interest rate used by the daily interest payment
// batch (Account.ApplyInterest). Same principle as RateLimitConfig — a
// default that works out of the box for local development, with production
// values overridden via environment variables (not a Secrets Manager target
// per config.md, since it isn't a sensitive value).
type InterestConfig struct {
	DailyRate float64 // daily interest rate. Default 0.0001 = 0.01%
}

func LoadInterestConfig() InterestConfig {
	cfg := InterestConfig{DailyRate: 0.0001}

	if v := os.Getenv("INTEREST_DAILY_RATE"); v != "" {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			cfg.DailyRate = parsed
		}
	}

	return cfg
}
