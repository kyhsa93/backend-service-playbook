package persistence

import (
	"context"
	"database/sql"
)

type AccountRepository struct {
	db *sql.DB
}

// FindAccounts queries the accounts table (which has a deleted_at column) but
// omits the filter — a violation case.
func (r *AccountRepository) FindAccounts(ctx context.Context, ownerID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM accounts WHERE owner_id = $1`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
