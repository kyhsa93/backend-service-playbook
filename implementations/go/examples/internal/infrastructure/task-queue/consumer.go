package taskqueue

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Handler is a function that processes a single taskType. What's registered
// in main.go as a value of this map is always a Task Controller method from
// interface/task/ — the Task Controller has no logic of its own and
// delegates to a Command Service, and this package only does routing,
// looking up that function by the taskType string (the same separation of
// responsibilities as outbox.Consumer looking up and calling an
// application/event EventHandler).
type Handler func(ctx context.Context, payload []byte) error

// Consumer waits to receive from the Task Queue (SQS) via long polling, and
// when a message arrives, looks up a Task Controller in the handlers map by
// taskType (MessageAttributes) and calls it.
//
// Handler success → message deleted (ack). Handler failure (or no
// registered handler) → not deleted — once SQS's visibility timeout
// passes, it's automatically redelivered (at-least-once; scheduling.md's
// "Task handlers are idempotent" is exactly what assumes this
// redelivery).
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string]Handler) *Consumer {
	return &Consumer{sqs: sqsClient, queueURL: queueURL, handlers: handlers}
}

// Run is a background loop started exactly once as a goroutine in main()
// (the same shape as outbox.Consumer.Run). When ctx is cancelled
// (signal.NotifyContext), it waits out any in-flight ReceiveMessage (up to
// WaitTimeSeconds) before exiting.
func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}

		result, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:              aws.String(c.queueURL),
			MaxNumberOfMessages:   10,
			MessageAttributeNames: []string{"taskType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return // ReceiveMessage was cancelled during graceful shutdown
			}
			slog.ErrorContext(ctx, "task queue receive failed", "error", err)
			continue
		}

		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	taskType := ""
	if attr, ok := message.MessageAttributes["taskType"]; ok && attr.StringValue != nil {
		taskType = *attr.StringValue
	}

	handler, ok := c.handlers[taskType]
	if !ok {
		slog.ErrorContext(ctx, "no registered task controller found — leaving for retry", "task_type", taskType)
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	// The error is propagated as-is; it's only logged here, not deleted —
	// the Task Controller itself never catches and translates it
	// (scheduling.md, "let errors propagate as-is").
	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "task processing failed", "task_type", taskType, "error", err)
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "message deletion failed", "task_type", taskType, "error", err)
	}
}
