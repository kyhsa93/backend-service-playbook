package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/example/account-service/internal/domain/account"
)

type AccountRepository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증
var _ account.Repository = (*AccountRepository)(nil)

func NewAccountRepository(db *sql.DB) *AccountRepository {
	return &AccountRepository{db: db}
}

func (r *AccountRepository) FindByID(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT id, owner_id, amount, currency, status, created_at, updated_at
		 FROM accounts WHERE id = $1 AND owner_id = $2 AND deleted_at IS NULL`,
		accountID, ownerID,
	)
	var id, ownerIDCol, currency, status string
	var amount int64
	var createdAt, updatedAt time.Time
	if err := row.Scan(&id, &ownerIDCol, &amount, &currency, &status, &createdAt, &updatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, account.ErrNotFound
		}
		return nil, fmt.Errorf("find account by id: %w", err)
	}
	balance, err := account.NewMoney(amount, currency)
	if err != nil {
		return nil, err
	}
	return account.Reconstitute(id, ownerIDCol, balance, account.Status(status), createdAt, updatedAt), nil
}

func (r *AccountRepository) FindAll(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	args := []any{}
	where := []string{"deleted_at IS NULL"}
	i := 1

	if q.AccountID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.AccountID)
		i++
	}
	if q.OwnerID != "" {
		where = append(where, fmt.Sprintf("owner_id = $%d", i))
		args = append(args, q.OwnerID)
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
		fmt.Sprintf(`SELECT COUNT(*) FROM accounts WHERE %s`, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count accounts: %w", err)
	}

	args = append(args, q.Take, q.Page*q.Take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(`SELECT id, owner_id, amount, currency, status, created_at, updated_at
		 FROM accounts WHERE %s ORDER BY id DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find accounts: %w", err)
	}
	defer rows.Close()

	var accounts []*account.Account
	for rows.Next() {
		var id, ownerID, currency, status string
		var amount int64
		var createdAt, updatedAt time.Time
		if err := rows.Scan(&id, &ownerID, &amount, &currency, &status, &createdAt, &updatedAt); err != nil {
			return nil, 0, err
		}
		balance, err := account.NewMoney(amount, currency)
		if err != nil {
			return nil, 0, err
		}
		accounts = append(accounts, account.Reconstitute(id, ownerID, balance, account.Status(status), createdAt, updatedAt))
	}
	return accounts, total, rows.Err()
}

func (r *AccountRepository) Save(ctx context.Context, a *account.Account) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx,
		`INSERT INTO accounts (id, owner_id, amount, currency, status, updated_at)
		 VALUES ($1, $2, $3, $4, $5, NOW())
		 ON CONFLICT (id) DO UPDATE SET amount = EXCLUDED.amount, status = EXCLUDED.status, updated_at = NOW()`,
		a.AccountID, a.OwnerID, a.Balance.Amount, a.Balance.Currency, string(a.Status),
	)
	if err != nil {
		return fmt.Errorf("save account: %w", err)
	}

	for _, t := range a.PendingTransactions() {
		_, err = tx.ExecContext(ctx,
			`INSERT INTO transactions (id, account_id, type, amount, currency, created_at)
			 VALUES ($1, $2, $3, $4, $5, $6)`,
			t.TransactionID, t.AccountID, string(t.Type), t.Amount.Amount, t.Amount.Currency, t.CreatedAt,
		)
		if err != nil {
			return fmt.Errorf("save transaction: %w", err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearTransactions()
	return nil
}

func (r *AccountRepository) FindTransactions(ctx context.Context, accountID string, page, take int) ([]account.Transaction, int, error) {
	var total int
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM transactions WHERE account_id = $1`, accountID,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count transactions: %w", err)
	}

	rows, err := r.db.QueryContext(ctx,
		`SELECT id, account_id, type, amount, currency, created_at
		 FROM transactions WHERE account_id = $1
		 ORDER BY created_at DESC LIMIT $2 OFFSET $3`,
		accountID, take, page*take,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find transactions: %w", err)
	}
	defer rows.Close()

	var transactions []account.Transaction
	for rows.Next() {
		var t account.Transaction
		var txType, currency string
		var amount int64
		if err := rows.Scan(&t.TransactionID, &t.AccountID, &txType, &amount, &currency, &t.CreatedAt); err != nil {
			return nil, 0, err
		}
		t.Type = account.TransactionType(txType)
		t.Amount = account.Money{Amount: amount, Currency: currency}
		transactions = append(transactions, t)
	}
	return transactions, total, rows.Err()
}
