package scheduling

import (
	"context"
	"log/slog"
	"time"
)

// InterestScheduler loads a "pay interest on every ACTIVE account" Task
// once a day — it contains no business logic at all (interest calculation,
// balance updates). Actual execution is delegated through the path Task
// Consumer → interface/task.InterestTaskController →
// command.ApplyDailyInterestHandler (scheduling.md, "Scheduler role
// separation").
type InterestScheduler struct {
	taskQueue TaskQueue
}

func NewInterestScheduler(taskQueue TaskQueue) *InterestScheduler {
	return &InterestScheduler{taskQueue: taskQueue}
}

// Run repeats enqueue on a 24-hour cycle until ctx is cancelled. Actual
// reliability is handled not by this tick's exact timing but by (1) the
// task_outbox.dedup_id UNIQUE constraint and (2) Account.ApplyInterest's
// date-based idempotency, so it stays safe across restarts and approximate
// tick drift.
func (s *InterestScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.EnqueueDailyInterest(ctx, time.Now().UTC()); err != nil {
				// Many scheduling libraries silently swallow Cron exceptions —
				// this one always logs it explicitly (scheduling.md). It is
				// retried on the next tick (24 hours later), so it's not
				// re-thrown here.
				slog.ErrorContext(ctx, "interest payment task enqueue failed", "error", err)
			}
		}
	}
}

// EnqueueDailyInterest loads the interest-payment Task for one day, today
// (UTC). Run calls it internally, but it's exported so tests or ops
// tooling can call it directly without waiting for an actual tick (the
// same pattern shown by scheduling.md's example — since the Cron handler
// is a thin function that only enqueues, verifying it directly is
// sufficient).
func (s *InterestScheduler) EnqueueDailyInterest(ctx context.Context, today time.Time) error {
	date := today.Format("2006-01-02")
	dedupID := "account.apply-interest-" + date
	payload := []byte(`{"date":"` + date + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.apply-interest", payload, dedupID)
}
