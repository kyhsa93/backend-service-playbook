package taskqueue

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

// Poller reads processed=false rows from task_outbox and publishes them to
// the Task Queue (SQS) — it repeats the exact same role and shape as the
// Poller in internal/infrastructure/outbox/poller.go, for a separate table/
// queue (it also preserves the same invariant of not synchronously
// draining right after save within the same process — Run runs
// independently and periodically as a goroutine in main()).
//
// processed=true doesn't mean "the Task Consumer finished processing" — it
// means "delivery to SQS finished." From here on, retry/at-least-once
// guarantees are handled not by task_outbox but by SQS's visibility
// timeout + DLQ.
type Poller struct {
	db       *sql.DB
	sqs      *sqs.Client
	queueURL string
}

func NewPoller(db *sql.DB, sqsClient *sqs.Client, queueURL string) *Poller {
	return &Poller{db: db, sqs: sqsClient, queueURL: queueURL}
}

type taskOutboxRow struct {
	taskID   string
	taskType string
	payload  []byte
}

// Run repeatedly runs drainOnce every 1 second (the same period as
// outbox.Poller) and stops when ctx is cancelled (main()'s
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
				slog.ErrorContext(ctx, "task outbox polling failed", "error", err)
			}
		}
	}
}

func (p *Poller) drainOnce(ctx context.Context) error {
	rows, err := p.fetchPending(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "task queue publish failed",
				"task_type", row.taskType,
				"task_id", row.taskID,
				"error", err,
			)
			// A row that failed to publish is left as processed=false so it's retried on the next tick.
		}
	}
	return nil
}

func (p *Poller) fetchPending(ctx context.Context) ([]taskOutboxRow, error) {
	rows, err := p.db.QueryContext(ctx,
		`SELECT task_id, task_type, payload FROM task_outbox WHERE processed = false ORDER BY created_at LIMIT 100`)
	if err != nil {
		return nil, fmt.Errorf("query pending task_outbox rows: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var pending []taskOutboxRow
	for rows.Next() {
		var row taskOutboxRow
		if err := rows.Scan(&row.taskID, &row.taskType, &row.payload); err != nil {
			return nil, fmt.Errorf("scan task_outbox row: %w", err)
		}
		pending = append(pending, row)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate task_outbox rows: %w", err)
	}
	return pending, nil
}

func (p *Poller) publish(ctx context.Context, row taskOutboxRow) error {
	if _, err := p.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(row.payload)),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"taskType": {DataType: aws.String("String"), StringValue: aws.String(row.taskType)},
		},
	}); err != nil {
		return fmt.Errorf("send sqs message: %w", err)
	}
	if _, err := p.db.ExecContext(ctx,
		`UPDATE task_outbox SET processed = true WHERE task_id = $1`, row.taskID,
	); err != nil {
		return fmt.Errorf("mark task_outbox row processed: %w", err)
	}
	return nil
}
