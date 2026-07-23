package outbox

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Poller reads processed=false rows from the outbox table and publishes
// them to SQS ("carries events piled up in the DB over to the queue"). It
// never calls any Handler directly — that's the Consumer's job.
//
// No Command Handler ever references this struct. The very fact that Run
// runs independently and periodically as a goroutine in main() is what
// eliminates "synchronous drain within the same process right after save"
// — even after a Command has committed its save and returned a response,
// there's no way to know when the event actually goes out to the queue
// until the next tick (up to 1 second later).
//
// processed=true no longer means "the handler finished processing" — it
// means "delivery to SQS finished." From here on, retry/at-least-once
// guarantees are handled not by the outbox table but by SQS's visibility
// timeout + DLQ (see docs/architecture/domain-events.md).
type Poller struct {
	db       *sql.DB
	sqs      *sqs.Client
	queueURL string
}

func NewPoller(db *sql.DB, sqsClient *sqs.Client, queueURL string) *Poller {
	return &Poller{db: db, sqs: sqsClient, queueURL: queueURL}
}

type outboxRow struct {
	eventID     string
	eventType   string
	payload     []byte
	traceParent sql.NullString
}

// Run repeatedly runs drainOnce every 1 second (same as nestjs's
// @Interval(1000)) and stops when ctx is cancelled (main()'s
// signal.NotifyContext).
func (p *Poller) Run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := p.drainOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "outbox polling failed", "error", err)
			}
		}
	}
}

// drainOnce processes only one tick's worth — even if the handler for an
// event published in this pass loads a new outbox row (e.g. the
// AccountSuspended handler loading account.suspended.v1), that row is
// naturally picked up by the Poller on the next tick. It does not loop
// infinitely/recursively "as long as there's progress" — that handling now
// happens naturally through the time gap between the Consumer (running
// handlers) and the Poller (publishing), so the old
// Relay.ProcessPending's multi-pass loop is no longer needed.
func (p *Poller) drainOnce(ctx context.Context) error {
	rows, err := p.fetchPending(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "SQS publish failed",
				"event_type", row.eventType,
				"event_id", row.eventID,
				"error", err,
			)
			// A row that failed to publish is left as processed=false so it's retried on the next tick.
		}
	}
	return nil
}

func (p *Poller) fetchPending(ctx context.Context) ([]outboxRow, error) {
	rows, err := p.db.QueryContext(ctx,
		`SELECT event_id, event_type, payload, trace_parent FROM outbox WHERE processed = false ORDER BY created_at LIMIT 100`)
	if err != nil {
		return nil, fmt.Errorf("query pending outbox rows: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var pending []outboxRow
	for rows.Next() {
		var row outboxRow
		if err := rows.Scan(&row.eventID, &row.eventType, &row.payload, &row.traceParent); err != nil {
			return nil, fmt.Errorf("scan outbox row: %w", err)
		}
		pending = append(pending, row)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate outbox rows: %w", err)
	}
	return pending, nil
}

func (p *Poller) publish(ctx context.Context, row outboxRow) error {
	attributes := map[string]types.MessageAttributeValue{
		"eventType": {DataType: aws.String("String"), StringValue: aws.String(row.eventType)},
	}
	// Only set when the row that produced this event carried an active
	// span (traceParentFromContext in trace_context.go) — a Task Queue
	// batch job, for instance, has nothing to propagate.
	if row.traceParent.Valid {
		attributes["traceparent"] = types.MessageAttributeValue{DataType: aws.String("String"), StringValue: aws.String(row.traceParent.String)}
	}
	if _, err := p.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:          aws.String(p.queueURL),
		MessageBody:       aws.String(string(row.payload)),
		MessageAttributes: attributes,
	}); err != nil {
		return fmt.Errorf("send sqs message: %w", err)
	}
	if _, err := p.db.ExecContext(ctx,
		`UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID,
	); err != nil {
		return fmt.Errorf("mark outbox row processed: %w", err)
	}
	return nil
}
