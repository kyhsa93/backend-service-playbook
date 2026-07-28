package command

import "github.com/example/account-service/internal/domain/account"

// SpendingHistoryPoint is one month's worth of the training signal — reuses
// the spending_analysis read model AnalyzeMonthlySpendingHandler already
// produces, rather than re-aggregating raw Transaction rows.
type SpendingHistoryPoint struct {
	AnalysisMonth string
	TotalAmount   int64
}

// SpendingForecastPrediction is SpendingForecastModel.Predict's output —
// the model's point estimate plus a qualitative confidence label.
type SpendingForecastPrediction struct {
	PredictedAmount int64
	Confidence      account.ForecastConfidence
}

// SpendingForecastModel is a Technical Service (see root
// docs/architecture/domain-service.md) — the core of this feature is a
// statistical model, an implementation concern independent of any domain
// rule, so it's abstracted the same way query.NlTransactionQueryTranslator
// abstracts an LLM call, and defined here in the Application layer (next to
// its only caller, ForecastSpendingHandler) for the same reason those two
// ports live in application/query/ next to AskTransactionHistoryHandler.
// Predict takes/returns plain data only, never a domain type beyond the
// ForecastConfidence enum, so the implementation (currently an in-process
// linear regression, internal/infrastructure/forecasting) could later be
// swapped for a call to an external ML service without touching any caller.
type SpendingForecastModel interface {
	// Predict fits a fresh model from history (which must already be in
	// chronological, oldest-first order — see
	// account.SpendingAnalysisRepository.FindRecentAnalyses) and
	// extrapolates one month forward. Callers are expected to enforce a
	// minimum history length before calling this — see
	// minHistoryMonthsForForecast in forecast_spending_handler.go — since a
	// history of fewer than 3 points makes the fit's R² trivially perfect
	// and therefore meaningless.
	Predict(history []SpendingHistoryPoint) SpendingForecastPrediction
}
