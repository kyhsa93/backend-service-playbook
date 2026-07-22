package persistence

import (
	"context"
	"database/sql"
)

type AccountRepository struct {
	db *sql.DB
}

func (r *AccountRepository) FindAccounts(ctx context.Context, ownerID string) ([]string, error) {
	where := []string{"deleted_at IS NULL"}
	_ = where
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM accounts WHERE deleted_at IS NULL AND owner_id = $1`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}

// FindTransactions queries the transactions table — this table has no
// deleted_at column (per the migrations), so missing the filter here is not a
// violation.
func (r *AccountRepository) FindTransactions(ctx context.Context, accountID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM transactions WHERE account_id = $1`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
