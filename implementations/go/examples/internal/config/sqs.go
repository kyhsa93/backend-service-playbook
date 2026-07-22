package config

import (
	"fmt"
	"os"
)

// SQSConfig holds the queue URLs shared by the Poller/Consumer. Validated
// fail-fast just like DatabaseConfig — if a value is missing, it's better to
// fail immediately than to start up in a state where events/Tasks silently
// never make it out to the queue.
//
// QueueURL (Domain/Integration Event, "a fact occurred") and TaskQueueURL
// (Task Queue, "command: perform X") are different SQS queues — the
// conceptual distinction that domain-events.md defines is preserved all the
// way down at the infrastructure level too (event_type and task_type are
// never mixed into the same queue).
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
