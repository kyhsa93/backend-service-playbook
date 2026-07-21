package config

import (
	"os"
	"strconv"
)

// InterestConfig는 일일 이자 지급 배치(Account.ApplyInterest)가 쓰는 이자율이다.
// RateLimitConfig와 동일한 원칙 — 로컬 개발에서 바로 동작하는 기본값을 두고,
// 운영값은 환경 변수로 override한다(민감값이 아니므로 config.md의 Secrets Manager
// 대상이 아니다).
type InterestConfig struct {
	DailyRate float64 // 일 이자율. 기본값 0.0001 = 0.01%
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
