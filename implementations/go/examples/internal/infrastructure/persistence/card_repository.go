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

// Compile-time interface satisfaction check — CardRepository satisfies both
// Repository (Command) and Query (the same structural typing idiom as
// account_repository.go).
var _ card.Repository = (*CardRepository)(nil)
var _ card.Query = (*CardRepository)(nil)

// NewCardRepository creates a Card repository. Since Card doesn't raise
// domain events, it doesn't take an outbox.Writer as a dependency, unlike
// AccountRepository.
func NewCardRepository(db *sql.DB) *CardRepository {
	return &CardRepository{db: db}
}

func (r *CardRepository) FindCards(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
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
		fmt.Sprintf(`SELECT id, account_id, owner_id, brand, status, created_at, last_statement_sent_month
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
		var lastStatementSentMonth sql.NullString
		if err := rows.Scan(&id, &accountID, &ownerID, &brand, &status, &createdAt, &lastStatementSentMonth); err != nil {
			return nil, 0, err
		}
		cards = append(cards, card.Reconstitute(id, accountID, ownerID, brand, card.Status(status), createdAt, lastStatementSentMonth.String))
	}
	return cards, total, rows.Err()
}

func (r *CardRepository) SaveCard(ctx context.Context, c *card.Card) error {
	var lastStatementSentMonth any
	if c.LastStatementSentMonth != "" {
		lastStatementSentMonth = c.LastStatementSentMonth
	}
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO cards (id, account_id, owner_id, brand, status, created_at, updated_at, last_statement_sent_month)
		 VALUES ($1, $2, $3, $4, $5, $6, NOW(), $7)
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = NOW(),
		   last_statement_sent_month = EXCLUDED.last_statement_sent_month`,
		c.CardID, c.AccountID, c.OwnerID, c.Brand, string(c.Status), c.CreatedAt, lastStatementSentMonth,
	)
	if err != nil {
		return fmt.Errorf("save card: %w", err)
	}
	return nil
}
