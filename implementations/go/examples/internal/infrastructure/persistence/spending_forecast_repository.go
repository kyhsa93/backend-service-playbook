package persistence

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountRepository also implements account.SpendingForecastRepository — the
// same "one concrete struct, several domain interfaces" idiom used for
// account.SpendingAnalysisRepository right above it.
var _ account.SpendingForecastRepository = (*AccountRepository)(nil)

// SaveForecast inserts a new spending_forecast row. ForecastID is always a
// freshly generated one (see account.NewSpendingForecast), so this is a
// plain INSERT rather than an upsert — a race that slips past the
// application-level HasForecast precheck surfaces as a unique-constraint
// violation on (account_id, forecast_month), the same "the DB constraint is
// the last line of defense" behavior as SaveAnalysis.
func (r *AccountRepository) SaveForecast(ctx context.Context, f *account.SpendingForecast) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO spending_forecast
		   (id, account_id, forecast_month, predicted_amount, confidence, history_months_used, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		f.ForecastID, f.AccountID, f.ForecastMonth, f.PredictedAmount, string(f.Confidence), f.HistoryMonthsUsed, f.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save spending forecast: %w", err)
	}
	return nil
}

// HasForecast implements the idempotency precheck required by
// account.SpendingForecastQuery.
func (r *AccountRepository) HasForecast(ctx context.Context, accountID, forecastMonth string) (bool, error) {
	var count int
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM spending_forecast WHERE account_id = $1 AND forecast_month = $2`,
		accountID, forecastMonth,
	).Scan(&count); err != nil {
		return false, fmt.Errorf("has spending forecast: %w", err)
	}
	return count > 0, nil
}

// FindForecast looks up the (accountId, forecastMonth) row, returning
// account.ErrSpendingForecastNotFound if none exists yet.
func (r *AccountRepository) FindForecast(ctx context.Context, accountID, forecastMonth string) (*account.SpendingForecast, error) {
	var f account.SpendingForecast
	var confidence string
	err := r.db.QueryRowContext(ctx,
		`SELECT id, account_id, forecast_month, predicted_amount, confidence, history_months_used, created_at
		 FROM spending_forecast WHERE account_id = $1 AND forecast_month = $2`,
		accountID, forecastMonth,
	).Scan(&f.ForecastID, &f.AccountID, &f.ForecastMonth, &f.PredictedAmount, &confidence, &f.HistoryMonthsUsed, &f.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, account.ErrSpendingForecastNotFound
		}
		return nil, fmt.Errorf("find spending forecast: %w", err)
	}
	f.Confidence = account.ForecastConfidence(confidence)
	return &f, nil
}
