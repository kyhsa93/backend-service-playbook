package account

import "context"

// SpendingAnalysisQuery is a Query-only interface for the spending_analysis
// read model — a separate interface from account.Query (the same "one
// domain package, multiple related-but-distinct read models" idiom as
// payment.RefundQuery alongside payment.Query).
type SpendingAnalysisQuery interface {
	// FindAnalysis looks up the (accountId, analysisMonth) row, returning
	// ErrSpendingAnalysisNotFound if none exists yet for that month.
	FindAnalysis(ctx context.Context, accountID, analysisMonth string) (*SpendingAnalysis, error)

	// HasAnalysis is a cheap idempotency check ahead of the real work — the
	// (accountId, analysisMonth) unique index on the table is the last line
	// of defense, the same two-layer pattern as
	// transactions.idx_transactions_reference_id_type.
	HasAnalysis(ctx context.Context, accountID, analysisMonth string) (bool, error)
}

// SpendingAnalysisRepository is a Command-only interface that adds a write
// method (SaveAnalysis) on top of SpendingAnalysisQuery's read methods.
type SpendingAnalysisRepository interface {
	SpendingAnalysisQuery
	SaveAnalysis(ctx context.Context, analysis *SpendingAnalysis) error

	// FindRecentAnalyses is the training data for ForecastSpendingHandler —
	// every analysis row strictly before beforeMonth, capped at limit,
	// returned oldest-first (chronological order), since
	// command.SpendingForecastModel.Predict treats slice position as the
	// month index. It lives on the write-capable Repository (not the
	// read-only Query) even though it never mutates anything, because its
	// only caller is ForecastSpendingHandler in application/command/ — a
	// Query-layer file referencing it would be harmless today, but keeping
	// every training-data lookup on the Repository side matches
	// SaveAnalysis/HasAnalysis's existing placement and avoids growing
	// SpendingAnalysisQuery with a method the HTTP-facing read path never
	// calls.
	FindRecentAnalyses(ctx context.Context, accountID, beforeMonth string, limit int) ([]SpendingAnalysis, error)
}
