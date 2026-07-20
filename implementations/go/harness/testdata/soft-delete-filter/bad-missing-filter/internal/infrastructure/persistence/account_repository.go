package persistence

import (
	"context"
	"database/sql"
)

type AccountRepository struct {
	db *sql.DB
}

// FindAccounts는 accounts 테이블(deleted_at 컬럼 있음)을 조회하지만 필터를 빠뜨린
// 위반 사례.
func (r *AccountRepository) FindAccounts(ctx context.Context, ownerID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM accounts WHERE owner_id = $1`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
