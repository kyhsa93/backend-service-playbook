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

// 컴파일 타임 interface 충족 검증 — AccountRepository는 Repository(Command)와
// Query 양쪽을 모두 만족한다. Go interface는 구조적 타이핑이므로
// 구현체를 별도로 두 벌 만들 필요 없이 같은 concrete struct가 양쪽 역할을 겸한다.
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

// SaveAccount는 앰비언트 트랜잭션(database.WithTx/Manager.RunInTx가 ctx에 심어둔
// *sql.Tx)이 있으면 거기 참여만 하고 커밋은 호출부(TransferHandler 등)에 맡긴다 —
// 없으면(기존의 모든 단독 호출부와 동일하게) 스스로 트랜잭션을 열고 커밋까지 책임진다.
// 두 경로 모두 실제 SQL은 saveAccount 하나를 공유하므로, 앰비언트 트랜잭션이 없는
// 기존 호출부의 동작은 한 글자도 바뀌지 않는다.
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
	if tx, ok := database.TxFromContext(ctx); ok {
		if err := r.saveAccount(ctx, tx, a); err != nil {
			return err
		}
		// 커밋은 앰비언트 트랜잭션 소유자(TransferHandler 등)의 몫이라 여기서 진짜
		// 커밋 성공을 확인할 수는 없지만, 이 경로를 쓰는 신규 유스케이스(Transfer)는
		// 같은 *Account 인스턴스를 이 호출 이후 재사용하거나 재시도하지 않으므로 무해하다.
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
	// 기존 동작과 동일하게, 실제 커밋이 성공을 확인한 뒤에만 in-memory pending 상태를
	// 비운다 — 커밋 실패 시 재시도가 거래/이벤트를 다시 시도할 수 있어야 한다.
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}

// saveAccount는 계좌 저장 + 거래 저장 + Outbox 적재 3개 statement를 주어진 tx로
// 실행한다 — 커밋/롤백은 호출부(SaveAccount 또는 앰비언트 트랜잭션 소유자)의 책임이다.
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

	// Outbox row는 계좌/거래 row와 같은 트랜잭션 안에서 적재된다 — 커밋이 원자적이므로
	// "계좌는 바뀌었는데 이벤트는 유실됨"(dual-write) 실패 모드가 존재하지 않는다.
	if err := r.outboxWriter.SaveAll(ctx, tx, a.DomainEvents()); err != nil {
		return err
	}

	return nil
}

// HasTransactionWithReference는 account.Query가 요구하는 멱등성 체크를 구현한다.
// (referenceID, type) 조합으로 확인해야 하는 이유는 account.Query 인터페이스의 주석 참고.
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
