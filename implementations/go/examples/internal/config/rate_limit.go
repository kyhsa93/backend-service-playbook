package config

import (
	"os"
	"strconv"
)

// RateLimitConfig는 전역 토큰 버킷 rate limiter의 임계값이다.
// 운영값은 환경 변수로 조정하고(설정되지 않으면 기본값을 쓴다), e2e 테스트처럼 같은 프로세스에서
// 짧은 시간에 수십 개 요청을 보내는 상황에서는 넉넉한 값으로 override한다.
type RateLimitConfig struct {
	RequestsPerSecond float64 // 초당 평균 허용 요청 수
	Burst             int     // 순간적으로 허용하는 burst 크기
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
