package scheduling

import (
	"context"
	"log/slog"
	"time"
)

// StatementScheduler loads a "send last month's usage statement for every
// ACTIVE card" Task once a month. Go's standard library has no Cron
// expression parser (scheduling.md), and a "month" period cannot be
// expressed with a fixed-length time.Ticker, so it's approximated with a
// daily tick + an "is today the 1st" gate — the responsibility for exact
// execution timing lies not here but with the task_outbox.dedup_id UNIQUE
// constraint (the same period is never loaded twice) and
// Card.MarkStatementSent (the same period is never sent again).
type StatementScheduler struct {
	taskQueue TaskQueue
}

func NewStatementScheduler(taskQueue TaskQueue) *StatementScheduler {
	return &StatementScheduler{taskQueue: taskQueue}
}

func (s *StatementScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := time.Now().UTC()
			if now.Day() != 1 {
				continue // enqueue only on the 1st of each month — otherwise it's a cheap no-op.
			}
			if err := s.EnqueueMonthlyStatement(ctx, now); err != nil {
				slog.ErrorContext(ctx, "card usage statement task enqueue failed", "error", err)
			}
		}
	}
}

// EnqueueMonthlyStatement loads the card usage statement Task for the
// month immediately preceding now (in "2006-01" format) — for example, if
// called on August 1st, it targets "2026-07" as the period. Run calls it
// internally, but it's exported so tests/ops tooling can call it directly
// without waiting for an actual tick (up to 24 hours).
func (s *StatementScheduler) EnqueueMonthlyStatement(ctx context.Context, now time.Time) error {
	period := previousMonth(now)
	dedupID := "card.send-usage-statement-" + period
	payload := []byte(`{"period":"` + period + `"}`)
	return s.taskQueue.Enqueue(ctx, "card.send-usage-statement", payload, dedupID)
}

// previousMonth returns the month immediately preceding the one now falls in, in "2006-01" format.
func previousMonth(now time.Time) string {
	firstOfThisMonth := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	lastMonth := firstOfThisMonth.AddDate(0, -1, 0)
	return lastMonth.Format("2006-01")
}
