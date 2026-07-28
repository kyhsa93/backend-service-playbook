package outbox

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"go.opentelemetry.io/otel"
)

// tracer names every span this package starts "outbox" (matching net/http's
// otelhttp instrumentation, which likewise names its spans after its own
// package) — see handleMessage.
var tracer = otel.Tracer("outbox")

// Handler is a function that processes a single event_type. payload is
// passed through exactly as the raw JSON that Writer.SaveAll/
// Publisher.Publish stored (deserialization is the Handler
// implementation's responsibility).
type Handler func(ctx context.Context, payload []byte) error

// Consumer waits to receive from SQS via long polling (ReceiveMessage's
// WaitTimeSeconds), and when a message arrives, looks up the handlers
// registered for that eventType (MessageAttributes) and calls every one of
// them — an eventType may have more than one subscriber (e.g. "MoneyWithdrawn"
// has both MoneyWithdrawnEventHandler and CategorizeTransactionEventHandler, see
// main.go). Each handler is independent: one subscriber's failure must not
// prevent a sibling subscriber on the same eventType from running, so every
// handler is given a chance to run on every delivery, and a failure is only
// decided after all of them have (see handleMessage/runHandlers below).
//
// All handlers succeeding → message deleted (ack). Any handler failing (or
// no registered handler at all) → not deleted — once SQS's visibility
// timeout passes, it's automatically redelivered (at-least-once). This
// redelivery is exactly what the EventHandler idempotency
// (docs/architecture/domain-events.md) this repository requires assumes —
// every handler on a shared eventType must already be idempotent, since a
// retry re-runs all of them again, including any that already succeeded.
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string][]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string][]Handler) *Consumer {
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
			MessageAttributeNames: []string{"eventType", "traceparent"},
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

	// Re-hydrates the span context Poller forwarded as the "traceparent"
	// message attribute (trace_context.go), then starts a new span as its
	// child — this is what makes the event-processing side show up in the
	// same trace as the HTTP request that originally wrote the outbox row
	// (observability.md). A row with no traceparent (e.g. a Task Queue-driven
	// event) leaves ctx unchanged, and tracer.Start still works — it just
	// starts a new, disconnected trace rather than erroring.
	if attr, ok := message.MessageAttributes["traceparent"]; ok && attr.StringValue != nil {
		ctx = contextWithTraceParent(ctx, *attr.StringValue)
	}
	ctx, span := tracer.Start(ctx, "outbox.consume "+eventType)
	defer span.End()

	handlers, ok := c.handlers[eventType]
	if !ok || len(handlers) == 0 {
		slog.ErrorContext(ctx, "no registered handler found — leaving for retry", "event_type", eventType)
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	if err := runHandlers(ctx, eventType, handlers, []byte(aws.ToString(message.Body))); err != nil {
		return // not deleted — will be redelivered and retried after the visibility timeout.
	}

	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "message deletion failed", "event_type", eventType, "error", err)
	}
}

// runHandlers calls every handler registered for eventType, even if an
// earlier one fails — each is independent, so one subscriber's failure must
// not prevent a sibling subscriber from running (see Consumer's doc
// comment). Returns the first error encountered, if any, only after every
// handler has had a chance to run, so the caller can decide whether to leave
// the message unacked for redelivery.
func runHandlers(ctx context.Context, eventType string, handlers []Handler, payload []byte) error {
	var firstErr error
	for _, handler := range handlers {
		if err := handler(ctx, payload); err != nil {
			slog.ErrorContext(ctx, "event processing failed", "event_type", eventType, "error", err)
			if firstErr == nil {
				firstErr = err
			}
		}
	}
	return firstErr
}
