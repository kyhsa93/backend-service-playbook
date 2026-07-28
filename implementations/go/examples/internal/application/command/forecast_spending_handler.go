package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// ForecastSpendingCommand is the input SpendingForecastTaskController
// (interface/task/) builds by deserializing the Task Queue message payload.
// ForecastMonth is in "2006-01" format — always the current month, since
// the job trains on spending_analysis history strictly before it (see
// Handle below). Unlike AnalyzeMonthlySpendingCommand, no date-boundary
// derivation is needed from this string at all: ForecastMonth is used only
// as an opaque key (HasForecast/SaveForecast/the beforeMonth bound passed to
// FindRecentAnalyses), never parsed into a time.Time.
type ForecastSpendingCommand struct {
	ForecastMonth string
}

// spendingForecastBatchSize uses 500 for the same reason as
// spendingAnalysisBatchSize/interestBatchSize/cardStatementBatchSize.
const spendingForecastBatchSize = 500

// minHistoryMonthsForForecast is a cold-start guard, not a tuning knob: 2
// points make any line "fit" perfectly (R² === 1 regardless of the actual
// trend), so 3 is the minimum for SpendingForecastModel's R² to mean
// anything. An account younger than 3 analyzed months is simply skipped and
// retried by next month's run once it has more history — the same
// "skip, don't fail" idempotency-adjacent posture as
// AnalyzeMonthlySpendingHandler skipping an already-analyzed account.
const minHistoryMonthsForForecast = 3

// maxHistoryMonthsForForecast caps how many trailing months of history
// train the model, so an old account with years of spending_analysis rows
// doesn't skew a forecast meant to track recent behavior.
const maxHistoryMonthsForForecast = 6

// ForecastSpendingHandler is a system-triggered use case driven by the Task
// Queue. It trains (fits) a fresh SpendingForecastModel per account from
// that account's own spending_analysis history on every run — there is no
// persisted "model weights" row separate from the forecast itself, the same
// simplicity tradeoff AnalyzeMonthlySpendingHandler makes upstream
// (recomputed monthly, not maintained incrementally). Output is a queryable
// read-model row, not a file.
type ForecastSpendingHandler struct {
	accounts  account.Query
	analyses  account.SpendingAnalysisRepository
	forecasts account.SpendingForecastRepository
	model     SpendingForecastModel
}

func NewForecastSpendingHandler(
	accounts account.Query,
	analyses account.SpendingAnalysisRepository,
	forecasts account.SpendingForecastRepository,
	model SpendingForecastModel,
) *ForecastSpendingHandler {
	return &ForecastSpendingHandler{accounts: accounts, analyses: analyses, forecasts: forecasts, model: model}
}

// Handle saves and returns immediately per account — there is no Domain
// Event/Outbox involvement in this use case at all (SpendingForecast is a
// plain read-model row, not an Aggregate that raises events), the same
// reasoning as AnalyzeMonthlySpendingHandler.
func (h *ForecastSpendingHandler) Handle(ctx context.Context, cmd ForecastSpendingCommand) error {
	for page := 0; ; page++ {
		accounts, total, err := h.accounts.FindAccounts(ctx, account.FindQuery{
			Status: []account.Status{account.StatusActive},
			Take:   spendingForecastBatchSize,
			Page:   page,
		})
		if err != nil {
			return fmt.Errorf("forecast spending: find accounts: %w", err)
		}

		for _, a := range accounts {
			alreadyForecasted, err := h.forecasts.HasForecast(ctx, a.AccountID, cmd.ForecastMonth)
			if err != nil {
				return fmt.Errorf("forecast spending: has forecast: %w", err)
			}
			if alreadyForecasted {
				continue // Skip: this account already has a forecast for this month.
			}

			history, err := h.analyses.FindRecentAnalyses(ctx, a.AccountID, cmd.ForecastMonth, maxHistoryMonthsForForecast)
			if err != nil {
				return fmt.Errorf("forecast spending: find recent analyses: %w", err)
			}
			if len(history) < minHistoryMonthsForForecast {
				continue // Skip: not enough history to train on yet — retried automatically next month.
			}

			points := make([]SpendingHistoryPoint, len(history))
			for i, h2 := range history {
				points[i] = SpendingHistoryPoint{AnalysisMonth: h2.AnalysisMonth, TotalAmount: h2.TotalAmount}
			}
			prediction := h.model.Predict(points)

			forecast := account.NewSpendingForecast(a.AccountID, cmd.ForecastMonth, prediction.PredictedAmount, prediction.Confidence, len(history))
			if err := h.forecasts.SaveForecast(ctx, forecast); err != nil {
				return fmt.Errorf("forecast spending: save forecast: %w", err)
			}
		}

		if len(accounts) == 0 || (page+1)*spendingForecastBatchSize >= total {
			break
		}
	}
	return nil
}
