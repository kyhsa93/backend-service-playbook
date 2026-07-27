package persistence

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountRepository also implements account.SpendingAnalysisRepository — the
// same "one concrete struct, several domain interfaces" idiom it already
// uses for account.Repository/account.Query, rather than a second struct —
// the spending_analysis table has no interesting relationship to model
// beyond "one row per (account_id, analysis_month)" so a dedicated
// persistence struct would add nothing.
var _ account.SpendingAnalysisRepository = (*AccountRepository)(nil)

// SaveAnalysis inserts a new spending_analysis row. AnalysisID is always a
// freshly generated one (see account.NewSpendingAnalysis), so this is a
// plain INSERT rather than an upsert — a race that slips past the
// application-level HasAnalysis precheck surfaces as a unique-constraint
// violation on (account_id, analysis_month), the same "the DB constraint is
// the last line of defense, and hitting it is a genuine error, not silently
// swallowed" behavior as transactions.idx_transactions_reference_id_type.
func (r *AccountRepository) SaveAnalysis(ctx context.Context, a *account.SpendingAnalysis) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO spending_analysis
		   (id, account_id, analysis_month, total_amount, transaction_count, average_amount, change_from_previous_month, trend, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		a.AnalysisID, a.AccountID, a.AnalysisMonth, a.TotalAmount, a.TransactionCount, a.AverageAmount, a.ChangeFromPreviousMonth, string(a.Trend), a.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save spending analysis: %w", err)
	}
	return nil
}

// HasAnalysis implements the idempotency precheck required by
// account.SpendingAnalysisQuery.
func (r *AccountRepository) HasAnalysis(ctx context.Context, accountID, analysisMonth string) (bool, error) {
	var count int
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM spending_analysis WHERE account_id = $1 AND analysis_month = $2`,
		accountID, analysisMonth,
	).Scan(&count); err != nil {
		return false, fmt.Errorf("has spending analysis: %w", err)
	}
	return count > 0, nil
}

// FindAnalysis looks up the (accountId, analysisMonth) row, returning
// account.ErrSpendingAnalysisNotFound if none exists yet.
func (r *AccountRepository) FindAnalysis(ctx context.Context, accountID, analysisMonth string) (*account.SpendingAnalysis, error) {
	var a account.SpendingAnalysis
	var trend string
	err := r.db.QueryRowContext(ctx,
		`SELECT id, account_id, analysis_month, total_amount, transaction_count, average_amount, change_from_previous_month, trend, created_at
		 FROM spending_analysis WHERE account_id = $1 AND analysis_month = $2`,
		accountID, analysisMonth,
	).Scan(&a.AnalysisID, &a.AccountID, &a.AnalysisMonth, &a.TotalAmount, &a.TransactionCount, &a.AverageAmount, &a.ChangeFromPreviousMonth, &trend, &a.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, account.ErrSpendingAnalysisNotFound
		}
		return nil, fmt.Errorf("find spending analysis: %w", err)
	}
	a.Trend = account.SpendingTrend(trend)
	return &a, nil
}
