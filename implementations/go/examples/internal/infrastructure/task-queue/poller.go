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

// Poller는 task_outbox의 processed=false 행을 읽어 Task Queue(SQS)로 발행한다 —
// internal/infrastructure/outbox/poller.go의 Poller와 완전히 같은 역할·모양을 별도
// 테이블/큐에 대해 반복한다(같은 프로세스 안에서 저장 직후 동기 드레인하지 않는다는
// 불변식도 동일하게 지킨다 — Run은 main()의 goroutine으로 독립적으로 주기 실행된다).
//
// processed=true는 "Task Consumer가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는
// 뜻이다 — 이후의 재시도/at-least-once 보장은 task_outbox가 아니라 SQS의 visibility
// timeout + DLQ가 담당한다.
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

// Run은 1초 간격(outbox.Poller와 동일한 주기)으로 drainOnce를 반복 실행하다가 ctx가
// 취소되면(main()의 signal.NotifyContext) 멈춘다.
func (p *Poller) Run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := p.drainOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "task outbox 폴링 실패", "error", err)
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
			slog.ErrorContext(ctx, "Task Queue 발행 실패",
				"task_type", row.taskType,
				"task_id", row.taskID,
				"error", err,
			)
			// 발행 실패 행은 processed=false로 남겨 다음 tick에서 재시도한다.
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
