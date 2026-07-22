package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/database"
	"github.com/example/account-service/internal/infrastructure/outbox"
)

type AccountRepository struct {
	db           *sql.DB
	outboxWriter *outbox.Writer
}

// Compile-time interface satisfaction check — AccountRepository satisfies
// both Repository (Command) and Query. Since Go interfaces use structural
// typing, the same concrete struct serves both roles without needing two
// separate implementations.
var _ account.Repository = (*AccountRepository)(nil)
var _ account.Query = (*AccountRepository)(nil)

func NewAccountRepository(db *sql.DB, outboxWriter *outbox.Writer) *AccountRepository {
	return &AccountRepository{db: db, outboxWriter: outboxWriter}
}

func (r *AccountRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
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
		fmt.Sprintf(`SELECT id, owner_id, email, amount, currency, status, created_at, updated_at, last_interest_paid_at
		 FROM accounts WHERE %s ORDER BY id DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find accounts: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var accounts []*account.Account
	for rows.Next() {
		var id, ownerID, email, currency, status string
		var amount int64
		var createdAt, updatedAt time.Time
		var lastInterestPaidAt sql.NullTime
		if err := rows.Scan(&id, &ownerID, &email, &amount, &currency, &status, &createdAt, &updatedAt, &lastInterestPaidAt); err != nil {
			return nil, 0, err
		}
		balance, err := account.NewMoney(amount, currency)
		if err != nil {
			return nil, 0, err
		}
		accounts = append(accounts, account.Reconstitute(id, ownerID, email, balance, account.Status(status), createdAt, updatedAt, lastInterestPaidAt.Time))
	}
	return accounts, total, rows.Err()
}

// SaveAccount, if there's an ambient transaction (the *sql.Tx that
// database.WithTx/Manager.RunInTx stashed in ctx), only participates in it
// and leaves committing to the caller (TransferHandler, etc.) — if there
// isn't one (same as every existing standalone caller), it opens its own
// transaction and is responsible for committing it too. Both paths share
// the same underlying SQL via saveAccount, so the behavior of existing
// callers with no ambient transaction doesn't change in the slightest.
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
	if tx, ok := database.TxFromContext(ctx); ok {
		if err := r.saveAccount(ctx, tx, a); err != nil {
			return err
		}
		// Committing is the ambient transaction owner's job (TransferHandler,
		// etc.), so we can't actually confirm commit success here — but this
		// is harmless because the new use case (Transfer) that takes this
		// path never reuses or retries the same *Account instance after this
		// call.
		a.ClearTransactions()
		a.ClearEvents()
		return nil
	}

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if err := r.saveAccount(ctx, tx, a); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	// Same as the existing behavior — the in-memory pending state is only
	// cleared after actually confirming commit success. On commit failure, a
	// retry must be able to try the transaction/event again.
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}

// saveAccount runs 3 statements — saving the account, saving the
// transaction, and loading the Outbox — using the given tx. Committing/
// rolling back is the caller's responsibility (SaveAccount or the ambient
// transaction owner).
func (r *AccountRepository) saveAccount(ctx context.Context, tx *sql.Tx, a *account.Account) error {
	var lastInterestPaidAt any
	if !a.LastInterestPaidAt.IsZero() {
		lastInterestPaidAt = a.LastInterestPaidAt
	}
	_, err := tx.ExecContext(ctx,
		`INSERT INTO accounts (id, owner_id, email, amount, currency, status, updated_at, last_interest_paid_at)
		 VALUES ($1, $2, $3, $4, $5, $6, NOW(), $7)
		 ON CONFLICT (id) DO UPDATE SET amount = EXCLUDED.amount, status = EXCLUDED.status,
		   updated_at = NOW(), last_interest_paid_at = EXCLUDED.last_interest_paid_at`,
		a.AccountID, a.OwnerID, a.Email, a.Balance.Amount, a.Balance.Currency, string(a.Status), lastInterestPaidAt,
	)
	if err != nil {
		return fmt.Errorf("save account: %w", err)
	}

	for _, t := range a.PendingTransactions() {
		var referenceID any
		if t.ReferenceID != "" {
			referenceID = t.ReferenceID
		}
		_, err = tx.ExecContext(ctx,
			`INSERT INTO transactions (id, account_id, type, amount, currency, reference_id, created_at)
			 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			t.TransactionID, t.AccountID, string(t.Type), t.Amount.Amount, t.Amount.Currency, referenceID, t.CreatedAt,
		)
		if err != nil {
			return fmt.Errorf("save transaction: %w", err)
		}
	}

	// The Outbox row is loaded within the same transaction as the account/
	// transaction rows — since the commit is atomic, the "account changed
	// but the event was lost" (dual-write) failure mode cannot occur.
	if err := r.outboxWriter.SaveAll(ctx, tx, a.DomainEvents()); err != nil {
		return err
	}

	return nil
}

// HasTransactionWithReference implements the idempotency check required by
// account.Query. See the account.Query interface's comment for why it must
// be checked as the (referenceID, type) combination.
func (r *AccountRepository) HasTransactionWithReference(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
	var count int
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM transactions WHERE reference_id = $1 AND type = $2`,
		referenceID, string(txType),
	).Scan(&count); err != nil {
		return false, fmt.Errorf("count transactions by reference: %w", err)
	}
	return count > 0, nil
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
	defer func() { _ = rows.Close() }()

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
