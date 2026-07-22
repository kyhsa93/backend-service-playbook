package outbox

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Handler is a function that processes a single event_type. payload is
// passed through exactly as the raw JSON that Writer.SaveAll/
// Publisher.Publish stored (deserialization is the Handler
// implementation's responsibility).
type Handler func(ctx context.Context, payload []byte) error

// Consumer waits to receive from SQS via long polling (ReceiveMessage's
// WaitTimeSeconds), and when a message arrives, looks up a handler in the
// handlers map by eventType (MessageAttributes) and calls it.
//
// Handler success → message deleted (ack). Handler failure (or no
// registered handler) → not deleted — once SQS's visibility timeout
// passes, it's automatically redelivered (at-least-once). This redelivery
// is exactly what the EventHandler idempotency
// (docs/architecture/domain-events.md) this repository requires assumes.
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string]Handler) *Consumer {
	return &Consumer{sqs: sqsClient, queueURL: queueURL, handlers: handlers}
}

// Run is a background loop started exactly once as a goroutine in main() —
// it is not created anew per request. When ctx is cancelled
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
			MessageAttributeNames: []string{"eventType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return // ReceiveMessage was cancelled during graceful shutdown
			}
			slog.ErrorContext(ctx, "SQS receive failed", "error", err)
			continue
		}

		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	eventType := ""
	if attr, ok := message.MessageAttributes["eventType"]; ok && attr.StringValue != nil {
		eventType = *attr.StringValue
	}

	handler, ok := c.handlers[eventType]
	if !ok {
		slog.ErrorContext(ctx, "no registered handler found — leaving for retry", "event_type", eventType)
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "event processing failed", "event_type", eventType, "error", err)
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "message deletion failed", "event_type", eventType, "error", err)
	}
}
