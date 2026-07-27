package scheduling

import (
	"context"
	"log/slog"
	"time"
)

// SpendingAnalysisScheduler loads a "compute last month's spending analysis
// for every ACTIVE account" Task once a month — the same "daily tick + is
// today the 1st gate" approximation as StatementScheduler, for the same
// reason (Go's standard library has no Cron expression parser, and a
// "month" period cannot be expressed with a fixed-length time.Ticker). Exact
// execution timing is not this type's responsibility — that lies with the
// task_outbox.dedup_id UNIQUE constraint (the same period is never loaded
// twice) and spending_analysis's (account_id, analysis_month) unique index
// (the same period is never analyzed twice for the same account).
type SpendingAnalysisScheduler struct {
	taskQueue TaskQueue
}

func NewSpendingAnalysisScheduler(taskQueue TaskQueue) *SpendingAnalysisScheduler {
	return &SpendingAnalysisScheduler{taskQueue: taskQueue}
}

func (s *SpendingAnalysisScheduler) Run(ctx context.Context) {
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
			if err := s.EnqueueMonthlySpendingAnalysis(ctx, now); err != nil {
				slog.ErrorContext(ctx, "monthly spending analysis task enqueue failed", "error", err)
			}
		}
	}
}

// EnqueueMonthlySpendingAnalysis loads the spending-analysis Task for the
// month immediately preceding now (in "2006-01" format) — for example, if
// called on August 1st, it targets "2026-07" as the analysis month. Run
// calls it internally, but it's exported so tests/ops tooling can call it
// directly without waiting for an actual tick (up to 24 hours) — the same
// pattern as EnqueueDailyInterest/EnqueueMonthlyStatement.
func (s *SpendingAnalysisScheduler) EnqueueMonthlySpendingAnalysis(ctx context.Context, now time.Time) error {
	analysisMonth, _, _ := PreviousSpendingAnalysisPeriod(now)
	dedupID := "account.analyze-monthly-spending-" + analysisMonth
	payload := []byte(`{"analysisMonth":"` + analysisMonth + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.analyze-monthly-spending", payload, dedupID)
}

// PreviousSpendingAnalysisPeriod computes the previous calendar month
// relative to now (UTC), as both a "2006-01" period string and its
// [monthStart, monthEnd) boundary — mirrors nestjs's
// computePreviousSpendingAnalysisPeriod's UTC calendar math, but only
// carries this much (not also "the month before that") since that
// additional boundary is instead re-derived deterministically from the
// period string alone inside command.spendingAnalysisPeriodRange (the same
// trade-off previousMonth/periodRange already make for the card statement
// batch). Exported (unlike the unexported previousMonth helper this reuses)
// so the scheduling e2e test can compute the exact same "last month" window
// to backdate transactions into, without duplicating this calculation.
func PreviousSpendingAnalysisPeriod(now time.Time) (analysisMonth string, monthStart, monthEnd time.Time) {
	analysisMonth = previousMonth(now)
	monthStart, _ = time.Parse("2006-01", analysisMonth)
	monthEnd = monthStart.AddDate(0, 1, 0)
	return analysisMonth, monthStart, monthEnd
}
