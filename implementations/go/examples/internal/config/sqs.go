package config

import (
	"fmt"
	"os"
)

// SQSConfig는 OutboxPoller/OutboxConsumer가 공유하는 Domain/Integration Event 큐의
// URL이다. DatabaseConfig와 동일하게 fail-fast로 검증한다 — 값이 없으면 이벤트가
// 조용히 큐로 나가지 못하는 상태로 기동되는 것보다 즉시 실패하는 편이 낫다.
type SQSConfig struct {
	QueueURL string
}

func LoadSQSConfig() (SQSConfig, error) {
	url := os.Getenv("SQS_DOMAIN_EVENT_QUEUE_URL")
	if url == "" {
		return SQSConfig{}, fmt.Errorf("config: SQS_DOMAIN_EVENT_QUEUE_URL is required")
	}
	return SQSConfig{QueueURL: url}, nil
}
