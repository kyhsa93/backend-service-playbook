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

// FindTransactions는 transactions 테이블을 조회한다 — 이 테이블에는 deleted_at 컬럼이
// 없으므로(마이그레이션 기준) 필터가 없어도 위반이 아니다.
func (r *AccountRepository) FindTransactions(ctx context.Context, accountID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM transactions WHERE account_id = $1`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
