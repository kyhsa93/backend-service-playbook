package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/example/account-service/internal/domain/card"
)

type CardRepository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증 — CardRepository는 Repository(Command)와 Query 양쪽을
// 모두 만족한다(account_repository.go와 동일한 구조적 타이핑 관용구).
var _ card.Repository = (*CardRepository)(nil)
var _ card.Query = (*CardRepository)(nil)

// NewCardRepository는 Card 저장소를 만든다. Card는 도메인 이벤트를 발생시키지 않으므로
// AccountRepository와 달리 outbox.Writer를 주입받지 않는다.
func NewCardRepository(db *sql.DB) *CardRepository {
	return &CardRepository{db: db}
}

func (r *CardRepository) FindByID(ctx context.Context, cardID, ownerID string) (*card.Card, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT id, account_id, owner_id, brand, status, created_at
		 FROM cards WHERE id = $1 AND owner_id = $2`,
		cardID, ownerID,
	)
	var id, accountID, ownerIDCol, brand, status string
	var createdAt time.Time
	if err := row.Scan(&id, &accountID, &ownerIDCol, &brand, &status, &createdAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, card.ErrNotFound
		}
		return nil, fmt.Errorf("find card by id: %w", err)
	}
	return card.Reconstitute(id, accountID, ownerIDCol, brand, card.Status(status), createdAt), nil
}

func (r *CardRepository) FindAll(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
	args := []any{}
	where := []string{"1 = 1"}
	i := 1

	if q.CardID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.CardID)
		i++
	}
	if q.OwnerID != "" {
		where = append(where, fmt.Sprintf("owner_id = $%d", i))
		args = append(args, q.OwnerID)
		i++
	}
	if q.AccountID != "" {
		where = append(where, fmt.Sprintf("account_id = $%d", i))
		args = append(args, q.AccountID)
		i++
	}
	if len(q.Status) > 0 {
		placeholders := make([]string, len(q.Status))
		for j, s := range q.Status {
			placeholders[j] = fmt.Sprintf("$%d", i)
			args = append(args, string(s))
			i++
		}
		where = append(where, fmt.Sprintf("status IN (%s)", strings.Join(placeholders, ",")))
	}

	whereClause := strings.Join(where, " AND ")

	var total int
	if err := r.db.QueryRowContext(ctx,
		fmt.Sprintf(`SELECT COUNT(*) FROM cards WHERE %s`, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count cards: %w", err)
	}

	take := q.Take
	if take <= 0 {
		take = 20
	}
	args = append(args, take, q.Page*take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(`SELECT id, account_id, owner_id, brand, status, created_at
		 FROM cards WHERE %s ORDER BY id DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find cards: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var cards []*card.Card
	for rows.Next() {
		var id, accountID, ownerID, brand, status string
		var createdAt time.Time
		if err := rows.Scan(&id, &accountID, &ownerID, &brand, &status, &createdAt); err != nil {
			return nil, 0, err
		}
		cards = append(cards, card.Reconstitute(id, accountID, ownerID, brand, card.Status(status), createdAt))
	}
	return cards, total, rows.Err()
}

func (r *CardRepository) Save(ctx context.Context, c *card.Card) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO cards (id, account_id, owner_id, brand, status, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, NOW())
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()`,
		c.CardID, c.AccountID, c.OwnerID, c.Brand, string(c.Status), c.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save card: %w", err)
	}
	return nil
}
