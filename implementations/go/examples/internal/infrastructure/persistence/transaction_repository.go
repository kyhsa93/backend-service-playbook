package persistence

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// TransactionRepository is a separate Repository/struct from
// AccountRepository — that one only ever inserts Transaction rows in bulk as
// a side effect of SaveAccount (Transaction rows are otherwise insert-only
// there). This is the find→modify-via-domain-method→save<Noun> cycle
// CategorizeTransactionEventHandler needs for the one field (category) that
// legitimately gets set after the fact (see
// docs/architecture/repository-pattern.md's "a Repository must not have an
// update method" rule).
type TransactionRepository struct {
	db *sql.DB
}

// Compile-time interface satisfaction check.
var _ account.TransactionRepository = (*TransactionRepository)(nil)

func NewTransactionRepository(db *sql.DB) *TransactionRepository {
	return &TransactionRepository{db: db}
}

func (r *TransactionRepository) FindTransaction(ctx context.Context, transactionID string) (*account.Transaction, error) {
	var t account.Transaction
	var txType, currency string
	var amount int64
	var referenceID, merchantName, category sql.NullString

	err := r.db.QueryRowContext(ctx,
		`SELECT id, account_id, type, amount, currency, reference_id, merchant_name, category, created_at
		 FROM transactions WHERE id = $1`,
		transactionID,
	).Scan(&t.TransactionID, &t.AccountID, &txType, &amount, &currency, &referenceID, &merchantName, &category, &t.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("find transaction: %w", err)
	}

	t.Type = account.TransactionType(txType)
	t.Amount = account.Money{Amount: amount, Currency: currency}
	t.ReferenceID = referenceID.String
	t.MerchantName = merchantName.String
	t.Category = account.TransactionCategory(category.String)
	return &t, nil
}

// SaveTransaction upserts the row — CategorizeTransactionEventHandler is the only
// caller, and it always reaches this via find→Transaction.Categorize→save,
// so the only column that can actually differ from what's already stored is
// category; ON CONFLICT DO UPDATE only touches that column.
func (r *TransactionRepository) SaveTransaction(ctx context.Context, t account.Transaction) error {
	var referenceID, merchantName, category any
	if t.ReferenceID != "" {
		referenceID = t.ReferenceID
	}
	if t.MerchantName != "" {
		merchantName = t.MerchantName
	}
	if t.Category != "" {
		category = string(t.Category)
	}

	_, err := r.db.ExecContext(ctx,
		`INSERT INTO transactions (id, account_id, type, amount, currency, reference_id, merchant_name, category, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		 ON CONFLICT (id) DO UPDATE SET category = EXCLUDED.category`,
		t.TransactionID, t.AccountID, string(t.Type), t.Amount.Amount, t.Amount.Currency, referenceID, merchantName, category, t.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save transaction: %w", err)
	}
	return nil
}
