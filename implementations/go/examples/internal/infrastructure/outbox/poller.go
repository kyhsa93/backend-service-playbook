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

// Poller는 outbox 테이블의 processed=false 행을 읽어 SQS로 발행한다("DB에 쌓인
// 이벤트를 큐로 실어 나른다"). 어떤 Handler도 직접 호출하지 않는다 — 그건
// Consumer의 몫이다.
//
// Command Handler는 이 struct를 전혀 참조하지 않는다. Run이 main()의 goroutine으로
// 독립적으로 주기 실행되는 것 자체가 "저장 직후 같은 프로세스 안에서 동기 드레인"을
// 제거하는 핵심이다 — Command가 저장을 커밋하고 응답을 반환한 뒤에도, 이벤트가
// 언제 큐로 나가는지는 다음 tick(최대 1초 뒤)까지 알 수 없다.
//
// processed=true는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는
// 뜻이다 — 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의
// visibility timeout + DLQ가 담당한다(docs/architecture/domain-events.md 참고).
type Poller struct {
	db       *sql.DB
	sqs      *sqs.Client
	queueURL string
}

func NewPoller(db *sql.DB, sqsClient *sqs.Client, queueURL string) *Poller {
	return &Poller{db: db, sqs: sqsClient, queueURL: queueURL}
}

type outboxRow struct {
	eventID   string
	eventType string
	payload   []byte
}

// Run은 1초 간격(nestjs의 @Interval(1000)과 동일)으로 drainOnce를 반복 실행하다가
// ctx가 취소되면(main()의 signal.NotifyContext) 멈춘다.
func (p *Poller) Run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := p.drainOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "outbox 폴링 실패", "error", err)
			}
		}
	}
}

// drainOnce는 한 tick만큼만 처리한다 — 이 패스에서 발행한 이벤트의 핸들러가 새
// outbox 행을 적재하더라도(예: AccountSuspended 핸들러가 account.suspended.v1을
// 적재) 그 행은 다음 tick의 Poller가 자연스럽게 집어간다. 무한 재귀적으로
// "진전이 있는 한" 반복하지 않는다 — 그 처리는 이제 Consumer(핸들러 실행)와
// Poller(발행) 사이의 시간 간극으로 자연히 이뤄지며, 옛 Relay.ProcessPending의
// 다중 패스 루프는 더 이상 필요하지 않다.
func (p *Poller) drainOnce(ctx context.Context) error {
	rows, err := p.fetchPending(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "SQS 발행 실패",
				"event_type", row.eventType,
				"event_id", row.eventID,
				"error", err,
			)
			// 발행 실패 행은 processed=false로 남겨 다음 tick에서 재시도한다.
		}
	}
	return nil
}

func (p *Poller) fetchPending(ctx context.Context) ([]outboxRow, error) {
	rows, err := p.db.QueryContext(ctx,
		`SELECT event_id, event_type, payload FROM outbox WHERE processed = false ORDER BY created_at LIMIT 100`)
	if err != nil {
		return nil, fmt.Errorf("query pending outbox rows: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var pending []outboxRow
	for rows.Next() {
		var row outboxRow
		if err := rows.Scan(&row.eventID, &row.eventType, &row.payload); err != nil {
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
	if _, err := p.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(row.payload)),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"eventType": {DataType: aws.String("String"), StringValue: aws.String(row.eventType)},
		},
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
