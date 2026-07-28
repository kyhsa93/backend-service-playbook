package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// ForecastConfidence classifies how well a linear trend fit
// SpendingForecastModel's training history — kept in the domain layer
// (rather than alongside the command.SpendingForecastModel Technical
// Service port in application/) since it's a value SpendingForecast (a
// domain type) holds directly. This split mirrors the exact fix nestjs's
// own harness required for the same feature: a type referenced by a domain
// struct's field cannot live in the application layer, even if the value is
// actually computed there.
type ForecastConfidence string

const (
	ForecastConfidenceLow    ForecastConfidence = "LOW"
	ForecastConfidenceMedium ForecastConfidence = "MEDIUM"
	ForecastConfidenceHigh   ForecastConfidence = "HIGH"
)

// SpendingForecast is a materialized read-model row — the ETL's precomputed
// answer to "what will this account likely spend this month," produced
// monthly by ForecastSpendingHandler (which trains a fresh
// command.SpendingForecastModel from the account's spending_analysis
// history on every run) and served as-is by GetSpendingForecastHandler. No
// business invariant lives here — the one real transform step (fitting the
// model) already happened before this struct is constructed — so this
// stays a plain struct rather than a stateful Aggregate, the same reasoning
// as SpendingAnalysis.
type SpendingForecast struct {
	ForecastID        string
	AccountID         string
	ForecastMonth     string
	PredictedAmount   int64
	Confidence        ForecastConfidence
	HistoryMonthsUsed int
	CreatedAt         time.Time
}

// NewSpendingForecast builds a forecast row from an already-computed
// prediction (see command.SpendingForecastModel.Predict) — unlike
// NewSpendingAnalysis, there is no further transform to perform here, the
// prediction is stored as-is.
func NewSpendingForecast(accountID, forecastMonth string, predictedAmount int64, confidence ForecastConfidence, historyMonthsUsed int) *SpendingForecast {
	return &SpendingForecast{
		ForecastID:        common.NewID(),
		AccountID:         accountID,
		ForecastMonth:     forecastMonth,
		PredictedAmount:   predictedAmount,
		Confidence:        confidence,
		HistoryMonthsUsed: historyMonthsUsed,
		CreatedAt:         time.Now(),
	}
}
