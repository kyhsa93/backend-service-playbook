package persistence

import (
	"context"
	"database/sql"
)

type CardRepository struct {
	db *sql.DB
}

// The cards table has no deleted_at column (per the migrations), so missing the filter here is not a violation.
func (r *CardRepository) FindCards(ctx context.Context, ownerID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM cards WHERE owner_id = $1`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
