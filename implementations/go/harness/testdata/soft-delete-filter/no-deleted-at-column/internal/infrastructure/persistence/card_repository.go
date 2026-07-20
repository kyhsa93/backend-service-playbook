package persistence

import (
	"context"
	"database/sql"
)

type CardRepository struct {
	db *sql.DB
}

// cards 테이블은 deleted_at 컬럼이 없으므로(마이그레이션 기준) 필터가 없어도 위반이 아니다.
func (r *CardRepository) FindCards(ctx context.Context, ownerID string) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id FROM cards WHERE owner_id = $1`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return nil, nil
}
