// Package taskqueue는 Task Queue 인프라(task_outbox 적재/발행/수신)를 담는다.
// internal/infrastructure/outbox/(Domain/Integration Event, "사실이 일어났다")와
// 구조는 거울상이지만 별도 테이블·별도 SQS 큐를 쓴다 — Task는 "명령: X를 수행하라"라는
// 다른 의미 단위이기 때문이다(docs/architecture/domain-events.md, "Task Queue vs
// Domain Event").
package taskqueue

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/example/account-service/internal/common"
)

// Writer는 Scheduler(Cron)나 Command 트랜잭션이 Task를 task_outbox 테이블에 적재하는
// 진입점이다(scheduling.md의 "TaskQueue" 포트를 구현한다). Scheduler는 별도 DB
// 트랜잭션 문맥이 없으므로, 이 단일 row INSERT 자체가 원자성의 전부가 된다 — Command
// 트랜잭션 안에서 호출할 때는 outbox.Writer.SaveAll과 동일하게 그 트랜잭션에 편승할
// 수도 있지만, 이 저장소의 두 배치(이자 지급/카드 사용내역)는 모두 Scheduler가
// 만드는 Task이므로 현재는 이 auto-commit 경로만 쓰인다.
//
// dedup_id에 걸린 UNIQUE 제약(migrations/0007_add_scheduling.sql)이 "같은 날짜/기간
// Task의 중복 적재 방지"를 실제로 강제한다 — ON CONFLICT DO NOTHING으로 두 번째
// 이후의 INSERT는 조용히 무시된다(여러 인스턴스가 동시에 같은 Cron tick을 실행해도
// 안전, scheduling.md의 "Cron 다중 인스턴스 안전성").
type Writer struct {
	db *sql.DB
}

func NewWriter(db *sql.DB) *Writer {
	return &Writer{db: db}
}

// Enqueue는 taskType/payload/dedupID로 task_outbox 행 하나를 적재한다. 같은 dedupID로
// 이미 적재된 행이 있으면 아무 것도 하지 않고 성공으로 반환한다(멱등한 enqueue).
func (w *Writer) Enqueue(ctx context.Context, taskType string, payload []byte, dedupID string) error {
	if _, err := w.db.ExecContext(ctx,
		`INSERT INTO task_outbox (task_id, task_type, payload, dedup_id) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (dedup_id) DO NOTHING`,
		common.NewID(), taskType, payload, dedupID,
	); err != nil {
		return fmt.Errorf("enqueue task %s: %w", taskType, err)
	}
	return nil
}
