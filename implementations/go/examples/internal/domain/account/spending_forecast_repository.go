package account

import "context"

// SpendingForecastQuery is a Query-only interface for the spending_forecast
// read model — the same "separate Query interface with no Repository in its
// name" idiom as SpendingAnalysisQuery, needed so application/query/ files
// can depend on it without tripping the cqrs-pattern harness rule (which
// FAILs on any reference to a write-capable *Repository type from the Query
// layer).
type SpendingForecastQuery interface {
	// FindForecast looks up the (accountId, forecastMonth) row, returning
	// ErrSpendingForecastNotFound if none exists yet — e.g. the account had
	// fewer than 3 months of spending_analysis history when the batch last ran.
	FindForecast(ctx context.Context, accountID, forecastMonth string) (*SpendingForecast, error)

	// HasForecast is a cheap idempotency check ahead of the real work — the
	// (accountId, forecastMonth) unique index on the table is the last line
	// of defense, the same two-layer pattern as SpendingAnalysisQuery.HasAnalysis.
	HasForecast(ctx context.Context, accountID, forecastMonth string) (bool, error)
}

// SpendingForecastRepository is a Command-only interface that adds a write
// method (SaveForecast) on top of SpendingForecastQuery's read methods.
type SpendingForecastRepository interface {
	SpendingForecastQuery
	SaveForecast(ctx context.Context, forecast *SpendingForecast) error
}
