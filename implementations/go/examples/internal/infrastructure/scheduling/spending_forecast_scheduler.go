package scheduling

import (
	"context"
	"log/slog"
	"time"

	"github.com/example/account-service/internal/common"
)

// SpendingForecastScheduler loads a "predict this month's spending for
// every ACTIVE account" Task once a month — the same "daily tick + is today
// the 1st gate" approximation as SpendingAnalysisScheduler, for the same
// reason (Go's standard library has no Cron expression parser, and a
// "month" period cannot be expressed with a fixed-length time.Ticker).
//
// nestjs's reference schedules this an hour after its spending-analysis job
// (02:00 vs 03:00 UTC) so this month's freshly-written analysis row is
// guaranteed to exist before training reads it. Go's ticker-based
// approximation has no equivalent sub-day precision to offer that same
// guarantee — but correctness does not depend on it: if this scheduler's
// tick lands before SpendingAnalysisScheduler's on the same day,
// ForecastSpendingHandler simply trains on one fewer month of history (or,
// if that pushes an account below minHistoryMonthsForForecast, skips it and
// retries automatically next month, the same graceful-degradation posture
// documented on that constant) — so both schedulers can safely tick
// independently with no enforced ordering between them.
type SpendingForecastScheduler struct {
	taskQueue TaskQueue
}

func NewSpendingForecastScheduler(taskQueue TaskQueue) *SpendingForecastScheduler {
	return &SpendingForecastScheduler{taskQueue: taskQueue}
}

func (s *SpendingForecastScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := common.Now()
			if now.Day() != 1 {
				continue // enqueue only on the 1st of each month — otherwise it's a cheap no-op.
			}
			if err := s.EnqueueMonthlySpendingForecast(ctx, now); err != nil {
				slog.ErrorContext(ctx, "monthly spending forecast task enqueue failed", "error", err)
			}
		}
	}
}

// EnqueueMonthlySpendingForecast loads the spending-forecast Task for the
// month now falls in (in "2006-01" format) — for example, if called on
// August 1st, it targets "2026-08" as the forecast month (the month that
// just started, trained on history strictly before it). Run calls it
// internally, but it's exported so tests/ops tooling can call it directly
// without waiting for an actual tick — the same pattern as
// EnqueueMonthlySpendingAnalysis.
func (s *SpendingForecastScheduler) EnqueueMonthlySpendingForecast(ctx context.Context, now time.Time) error {
	forecastMonth := now.Format("2006-01")
	dedupID := "account.forecast-spending-" + forecastMonth
	payload := []byte(`{"forecastMonth":"` + forecastMonth + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.forecast-spending", payload, dedupID)
}
