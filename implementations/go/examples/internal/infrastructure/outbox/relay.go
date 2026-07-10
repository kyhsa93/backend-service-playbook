package outbox

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
)

// Handler는 하나의 event_type을 처리하는 함수다. payload는 Writer.SaveAll이 저장한
// JSON 원문 그대로 전달된다(역직렬화는 Handler 구현체의 책임).
type Handler func(ctx context.Context, payload []byte) error

// Relay는 outbox 테이블에서 미처리(processed = false) 행을 모두 읽어 event_type에
// 매핑된 Handler를 실행한다. Command Handler가 저장(Save) 직후 동기적으로
// ProcessPending을 호출하는 방식이므로 별도 폴링 goroutine/스케줄러가 없다.
//
// 개별 행 처리가 실패하면 로그만 남기고 processed를 true로 표시하지 않는다 — 그 행은
// 다음 커맨드가 트리거하는 다음 ProcessPending 호출에서 다시 시도된다(at-least-once).
type Relay struct {
	db       *sql.DB
	handlers map[string]Handler
}

func NewRelay(db *sql.DB, handlers map[string]Handler) *Relay {
	return &Relay{db: db, handlers: handlers}
}

type outboxRow struct {
	eventID   string
	eventType string
	payload   []byte
}

// ProcessPending은 테이블 전체의 미처리 행을 순서대로 처리한다 — 자신이 방금 적재한
// 이벤트만이 아니라 다른 커맨드가 남겨둔 미처리 행까지 함께 드레인한다.
func (r *Relay) ProcessPending(ctx context.Context) error {
	rows, err := r.db.QueryContext(ctx,
		`SELECT event_id, event_type, payload FROM outbox WHERE processed = false ORDER BY created_at`)
	if err != nil {
		return fmt.Errorf("query pending outbox rows: %w", err)
	}

	var pending []outboxRow
	for rows.Next() {
		var row outboxRow
		if err := rows.Scan(&row.eventID, &row.eventType, &row.payload); err != nil {
			rows.Close()
			return fmt.Errorf("scan outbox row: %w", err)
		}
		pending = append(pending, row)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return fmt.Errorf("iterate outbox rows: %w", err)
	}
	rows.Close()

	for _, row := range pending {
		if err := r.processRow(ctx, row); err != nil {
			slog.ErrorContext(ctx, "outbox event processing failed",
				"event_type", row.eventType,
				"event_id", row.eventID,
				"error", err,
			)
		}
	}
	return nil
}

func (r *Relay) processRow(ctx context.Context, row outboxRow) error {
	if handler, ok := r.handlers[row.eventType]; ok {
		if err := handler(ctx, row.payload); err != nil {
			return err
		}
	}
	if _, err := r.db.ExecContext(ctx,
		`UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID,
	); err != nil {
		return fmt.Errorf("mark outbox row processed: %w", err)
	}
	return nil
}
