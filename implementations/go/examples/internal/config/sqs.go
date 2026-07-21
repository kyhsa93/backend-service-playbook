package config

import (
	"fmt"
	"os"
)

// SQSConfig는 Poller/Consumer가 공유하는 큐 URL들이다. DatabaseConfig와 동일하게
// fail-fast로 검증한다 — 값이 없으면 이벤트/Task가 조용히 큐로 나가지 못하는 상태로
// 기동되는 것보다 즉시 실패하는 편이 낫다.
//
// QueueURL(Domain/Integration Event, "사실이 일어났다")과 TaskQueueURL(Task Queue,
// "명령: X를 수행하라")은 서로 다른 SQS 큐다 — domain-events.md가 규정하는 개념적
// 구분을 인프라 수준에서도 그대로 유지한다(같은 큐에 event_type/task_type을 섞지
// 않는다).
type SQSConfig struct {
	QueueURL     string
	TaskQueueURL string
}

func LoadSQSConfig() (SQSConfig, error) {
	url := os.Getenv("SQS_DOMAIN_EVENT_QUEUE_URL")
	if url == "" {
		return SQSConfig{}, fmt.Errorf("config: SQS_DOMAIN_EVENT_QUEUE_URL is required")
	}
	taskURL := os.Getenv("SQS_TASK_QUEUE_URL")
	if taskURL == "" {
		return SQSConfig{}, fmt.Errorf("config: SQS_TASK_QUEUE_URL is required")
	}
	return SQSConfig{QueueURL: url, TaskQueueURL: taskURL}, nil
}
