package persistence

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"time"

	"github.com/example/account-service/internal/common"
	"github.com/example/account-service/internal/domain/payment"
)

// PaymentRepository handles both the Payment and Refund Aggregates — since
// Refund is a dependent concept that's meaningless without Payment, rather
// than building two separate Repository implementations, a single struct
// satisfies both payment.Repository/Query and payment.RefundRepository/
// Query (structural typing — the same idiom as account_repository.go).
type PaymentRepository struct {
	db *sql.DB
}

var _ payment.Repository = (*PaymentRepository)(nil)
var _ payment.Query = (*PaymentRepository)(nil)
var _ payment.RefundRepository = (*PaymentRepository)(nil)
var _ payment.RefundQuery = (*PaymentRepository)(nil)

func NewPaymentRepository(db *sql.DB) *PaymentRepository {
	return &PaymentRepository{db: db}
}

func (r *PaymentRepository) FindPayments(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
	args := []any{}
	where := []string{"1 = 1"}
	i := 1

	if q.PaymentID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.PaymentID)
		i++
	}
	if q.OwnerID != "" {
		where = append(where, fmt.Sprintf("owner_id = $%d", i))
		args = append(args, q.OwnerID)
		i++
	}
	if q.CardID != "" {
		where = append(where, fmt.Sprintf("card_id = $%d", i))
		args = append(args, q.CardID)
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
	if !q.CreatedFrom.IsZero() {
		where = append(where, fmt.Sprintf("created_at >= $%d", i))
		args = append(args, q.CreatedFrom)
		i++
	}
	if !q.CreatedTo.IsZero() {
		where = append(where, fmt.Sprintf("created_at < $%d", i))
		args = append(args, q.CreatedTo)
		i++
	}

	whereClause := strings.Join(where, " AND ")

	var total int
	if err := r.db.QueryRowContext(ctx,
		fmt.Sprintf(`SELECT COUNT(*) FROM payments WHERE %s`, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count payments: %w", err)
	}

	take := q.Take
	if take <= 0 {
		take = 20
	}
	args = append(args, take, q.Page*take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(`SELECT id, card_id, account_id, owner_id, amount, status, created_at
		 FROM payments WHERE %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find payments: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var payments []*payment.Payment
	for rows.Next() {
		var id, cardID, accountID, ownerID, status string
		var amount int64
		var createdAt time.Time
		if err := rows.Scan(&id, &cardID, &accountID, &ownerID, &amount, &status, &createdAt); err != nil {
			return nil, 0, err
		}
		payments = append(payments, payment.Reconstitute(id, cardID, accountID, ownerID, amount, payment.Status(status), createdAt))
	}
	return payments, total, rows.Err()
}

// Save commits the Payment row and the Outbox row in the same transaction
// (avoiding dual-write, domain-events.md). The shared outbox.Writer
// (internal/infrastructure/outbox/writer.go) currently has a signature
// fixed to []account.DomainEvent, so Payment's event slice can't be passed
// to it directly — until Writer is made generic, events are loaded
// directly within this transaction instead (the Relay operates based on
// the event_type string, so it drains normally regardless of how the row
// was loaded). This is the same workaround pattern used by the Repository
// that scripts/create-domain generates.
func (r *PaymentRepository) SavePayment(ctx context.Context, p *payment.Payment) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx,
		`INSERT INTO payments (id, card_id, account_id, owner_id, amount, status, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()`,
		p.PaymentID, p.CardID, p.AccountID, p.OwnerID, p.Amount, string(p.Status), p.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save payment: %w", err)
	}

	if err := insertOutboxEvents(ctx, tx, p.DomainEvents()); err != nil {
		return err
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save payment: %w", err)
	}
	p.ClearEvents()
	return nil
}

func (r *PaymentRepository) FindRefunds(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
	args := []any{}
	where := []string{"1 = 1"}
	i := 1

	if q.RefundID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.RefundID)
		i++
	}
	if q.PaymentID != "" {
		where = append(where, fmt.Sprintf("payment_id = $%d", i))
		args = append(args, q.PaymentID)
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
		fmt.Sprintf(`SELECT COUNT(*) FROM refunds WHERE %s`, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count refunds: %w", err)
	}

	take := q.Take
	if take <= 0 {
		take = 20
	}
	args = append(args, take, q.Page*take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(`SELECT id, payment_id, amount, reason, status, decision_note, created_at
		 FROM refunds WHERE %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find refunds: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var refunds []*payment.Refund
	for rows.Next() {
		var id, paymentID, reason, status string
		var decisionNote sql.NullString
		var amount int64
		var createdAt time.Time
		if err := rows.Scan(&id, &paymentID, &amount, &reason, &status, &decisionNote, &createdAt); err != nil {
			return nil, 0, err
		}
		refunds = append(refunds, payment.ReconstituteRefund(id, paymentID, amount, reason, payment.RefundStatus(status), decisionNote.String, createdAt))
	}
	return refunds, total, rows.Err()
}

// SaveRefund commits the Refund row and the Outbox row in the same transaction, using the same workaround pattern as Save.
func (r *PaymentRepository) SaveRefund(ctx context.Context, ref *payment.Refund) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var decisionNote any
	if ref.DecisionNote != "" {
		decisionNote = ref.DecisionNote
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO refunds (id, payment_id, amount, reason, status, decision_note, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, decision_note = EXCLUDED.decision_note, updated_at = NOW()`,
		ref.RefundID, ref.PaymentID, ref.Amount, ref.Reason, string(ref.Status), decisionNote, ref.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save refund: %w", err)
	}

	if err := insertOutboxEvents(ctx, tx, ref.DomainEvents()); err != nil {
		return err
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save refund: %w", err)
	}
	ref.ClearEvents()
	return nil
}

// insertOutboxEvents is the direct Outbox-loading helper shared by the
// Payment/Refund Aggregates (it consolidates the Save/SaveRefund workaround
// pattern in one place to reduce duplication).
func insertOutboxEvents(ctx context.Context, tx *sql.Tx, events []payment.DomainEvent) error {
	for _, evt := range events {
		body, err := json.Marshal(evt)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			common.NewID(), reflect.TypeOf(evt).Name(), body,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}
