// Package taskqueue holds the Task Queue infrastructure (task_outbox
// loading/publishing/receiving). Its structure mirrors
// internal/infrastructure/outbox/ (Domain/Integration Events, "a fact
// happened"), but it uses a separate table and a separate SQS queue —
// because a Task is a different semantic unit, "a command: do X"
// (docs/architecture/domain-events.md, "Task Queue vs Domain Event").
package taskqueue

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/example/account-service/internal/common"
)

// Writer is the entry point through which a Scheduler (Cron) or a Command
// transaction loads a Task into the task_outbox table (it implements the
// "TaskQueue" port from scheduling.md). A Scheduler has no separate DB
// transaction context, so this single-row INSERT is the entirety of its
// atomicity — when called from within a Command transaction, it could
// piggyback on that transaction the same way outbox.Writer.SaveAll does,
// but since both batches in this repository (interest payment/card usage
// statement) are Tasks created by a Scheduler, currently only this
// auto-commit path is used.
//
// The UNIQUE constraint on dedup_id (migrations/0007_add_scheduling.sql) is
// what actually enforces "prevent duplicate loading of a Task for the same
// date/period" — with ON CONFLICT DO NOTHING, any second or later INSERT
// is silently ignored (safe even if multiple instances run the same Cron
// tick concurrently — scheduling.md's "Cron multi-instance safety").
type Writer struct {
	db *sql.DB
}

func NewWriter(db *sql.DB) *Writer {
	return &Writer{db: db}
}

// Enqueue loads a single task_outbox row from taskType/payload/dedupID. If
// a row already exists with the same dedupID, it does nothing and still
// returns success (an idempotent enqueue).
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
