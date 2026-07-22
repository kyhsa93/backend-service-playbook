// Package database implements in Go the context-based Unit-of-Work required
// by the root docs/architecture/persistence.md ("transaction propagation")
// — an explicit version based on context.Context value propagation,
// corresponding to Node's AsyncLocalStorage-based TransactionManager and
// Spring's @Transactional.
//
// It's needed only when a single Handler calls writes on two Aggregates'
// Repositories at the same time (see go/docs/architecture/persistence.md) —
// account/application/command/transfer_handler.go (transferring money
// between accounts) is an example: unless the two SaveAccount calls
// (saving the source account and saving the target account) commit
// atomically together, a failure mode arises where only the withdrawal is
// reflected and the deposit is lost.
package database

import (
	"context"
	"database/sql"
	"fmt"
)

type txKey struct{}

// TxFromContext retrieves the *sql.Tx that WithTx (or Manager.RunInTx)
// stashed in ctx. It's used by a Repository to decide for itself "should I
// participate in the current ambient transaction, or should I open and
// commit a transaction of my own" (see SaveAccount in
// account_repository.go) — if every read/write path were unconditionally
// unified onto QuerierFrom, then when there's no ambient transaction, each
// single Exec statement would auto-commit individually, silently breaking
// the existing "account + transaction + outbox atomicity" guarantee. So
// Repository methods with multiple statements that need to decide who
// commits use this function, while paths where a single statement is
// enough, like lookups, use QuerierFrom below.
func TxFromContext(ctx context.Context) (*sql.Tx, bool) {
	tx, ok := ctx.Value(txKey{}).(*sql.Tx)
	return tx, ok
}

// WithTx starts a new transaction, stashes it in ctx, and runs fn. If fn
// finishes without an error, it commits; if fn returns an error (or panics
// internally, via the deferred Rollback), it rolls back. It's reentrant —
// if ctx already has a transaction, it reuses it as-is instead of opening a
// new one (the same reasoning as nestjs's TransactionManager.run()
// re-entering via AsyncLocalStorage).
func WithTx(ctx context.Context, db *sql.DB, fn func(ctx context.Context) error) error {
	if _, ok := TxFromContext(ctx); ok {
		return fn(ctx)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }() // no-op if already committed

	if err := fn(context.WithValue(ctx, txKey{}, tx)); err != nil {
		return err
	}
	return tx.Commit()
}

// Querier is the minimal interface commonly satisfied by both *sql.DB and
// *sql.Tx — used by single-statement callers that don't need to decide who
// commits, such as lookup paths.
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// QuerierFrom returns the ambient transaction if ctx has one, or the default db otherwise.
func QuerierFrom(ctx context.Context, db *sql.DB) Querier {
	if tx, ok := TxFromContext(ctx); ok {
		return tx
	}
	return db
}

// Manager is the implementation of the application/command.TransactionManager
// port — the Application layer never references this concrete type
// directly, depending only on that port interface (layer-architecture.md;
// since Go has no DI container, this is implemented via constructor
// injection).
type Manager struct {
	db *sql.DB
}

func NewManager(db *sql.DB) *Manager {
	return &Manager{db: db}
}

// RunInTx satisfies the command.TransactionManager signature.
func (m *Manager) RunInTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return WithTx(ctx, m.db, fn)
}
