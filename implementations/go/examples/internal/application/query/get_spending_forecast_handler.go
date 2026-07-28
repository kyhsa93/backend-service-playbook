package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// GetSpendingForecastQuery looks up an account's precomputed monthly
// spending forecast. ForecastMonth is in "2006-01" form.
type GetSpendingForecastQuery struct {
	AccountID     string
	RequesterID   string
	ForecastMonth string
}

// GetSpendingForecastHandler verifies account ownership (reusing
// account.FindOne, the same helper GetSpendingAnalysisHandler uses), then
// looks up the forecast row. Never trains a model on request — the query
// endpoint always serves the precomputed row ForecastSpendingHandler wrote.
type GetSpendingForecastHandler struct {
	accounts  account.Query
	forecasts account.SpendingForecastQuery
}

func NewGetSpendingForecastHandler(accounts account.Query, forecasts account.SpendingForecastQuery) *GetSpendingForecastHandler {
	return &GetSpendingForecastHandler{accounts: accounts, forecasts: forecasts}
}

func (h *GetSpendingForecastHandler) Handle(ctx context.Context, q GetSpendingForecastQuery) (*SpendingForecastResult, error) {
	if _, err := account.FindOne(ctx, h.accounts, q.AccountID, q.RequesterID); err != nil {
		return nil, fmt.Errorf("get spending forecast: %w", err)
	}

	forecast, err := h.forecasts.FindForecast(ctx, q.AccountID, q.ForecastMonth)
	if err != nil {
		return nil, fmt.Errorf("get spending forecast: %w", err)
	}

	return &SpendingForecastResult{
		ForecastMonth:     forecast.ForecastMonth,
		PredictedAmount:   forecast.PredictedAmount,
		Confidence:        string(forecast.Confidence),
		HistoryMonthsUsed: forecast.HistoryMonthsUsed,
		CreatedAt:         forecast.CreatedAt,
	}, nil
}
