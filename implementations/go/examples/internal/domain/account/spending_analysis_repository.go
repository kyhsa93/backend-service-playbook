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
}
