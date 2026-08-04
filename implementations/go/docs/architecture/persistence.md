# Persistence Pattern (Go) — Transactions, Soft Delete, Migrations

The principle follows the root [persistence.md](../../../../docs/architecture/persistence.md). The root document explicitly names, for transaction propagation, a language-specific context-local storage (Node's AsyncLocalStorage, Go's `context.Context`) — this document explains exactly how that should actually be implemented in Go, being careful to distinguish it from **what this repository's `examples/` actually does**.

---

## Transaction propagation — `context.Context`-based, actually implemented in `internal/infrastructure/database/`

### Root principle: implicit propagation via `context.Context`

Go's `context.Context` is the only standard value-propagation channel that crosses API boundaries. The actual implementation corresponding to the root's AsyncLocalStorage-based TransactionManager lives in `internal/infrastructure/database/transaction.go`:

```go
// internal/infrastructure/database/transaction.go — actual code
package database

type txKey struct{}

// TxFromContext pulls out the *sql.Tx that WithTx (or Manager.RunInTx) stashed
// in ctx. A Repository uses this to decide for itself whether it should join
// the current ambient transaction or open and commit its own.
func TxFromContext(ctx context.Context) (*sql.Tx, bool) {
	tx, ok := ctx.Value(txKey{}).(*sql.Tx)
	return tx, ok
}

// WithTx starts a new transaction, stashes it in ctx, and runs fn — it's
// re-entrant (if ctx already carries a transaction, it reuses it instead of opening a new one).
func WithTx(ctx context.Context, db *sql.DB, fn func(ctx context.Context) error) error {
	if _, ok := TxFromContext(ctx); ok {
		return fn(ctx)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if err := fn(context.WithValue(ctx, txKey{}, tx)); err != nil {
		return err
	}
	return tx.Commit()
}

// Querier is the minimal interface that both *sql.DB and *sql.Tx satisfy in
// common — used by single-statement call sites, like read paths, that don't
// need to decide who's responsible for committing.
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func QuerierFrom(ctx context.Context, db *sql.DB) Querier {
	if tx, ok := TxFromContext(ctx); ok {
		return tx
	}
	return db
}

// Manager is the implementation of the application/command.TransactionManager port.
type Manager struct{ db *sql.DB }

func NewManager(db *sql.DB) *Manager { return &Manager{db: db} }

func (m *Manager) RunInTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return WithTx(ctx, m.db, fn)
}
```

This pattern plays the same role as the root's AsyncLocalStorage-based TransactionManager — the difference is that while Node uses implicit storage (accessible even outside the callback), Go must **explicitly pass `context.Context` as a function argument** (a Go idiom — context is never hidden in a global variable or a struct field).

### `SaveAccount` decides for itself who's responsible for committing — why `QuerierFrom` isn't used unconditionally

`SaveAccount()` in `internal/infrastructure/persistence/account_repository.go` does **not** unconditionally call `QuerierFrom` the way a read path does. Instead, it checks directly with `TxFromContext` whether an ambient transaction exists, and decides the commit responsibility itself:

```go
// actual code
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
	if tx, ok := database.TxFromContext(ctx); ok {
		// only joins the ambient transaction — committing is the caller's job (e.g. TransferHandler).
		if err := r.saveAccount(ctx, tx, a); err != nil {
			return err
		}
		a.ClearTransactions()
		a.ClearEvents()
		return nil
	}

	// no ambient transaction — takes responsibility for opening and committing
	// itself, the same as every existing standalone call site (deposit/withdraw, etc.).
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
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}

// saveAccount is a private helper sharing the SQL for saving the account+transaction+Outbox —
// since both paths run exactly the same SQL, this refactor doesn't change the behavior of
// existing call sites without an ambient transaction by even a single character.
func (r *AccountRepository) saveAccount(ctx context.Context, tx *sql.Tx, a *account.Account) error { /* ... */ }
```

**Why `QuerierFrom(ctx, r.db)` isn't used unconditionally for every statement**: `outbox.Writer.SaveAll` is hard-typed to `*sql.Tx`, and more importantly, `SaveAccount` has always atomically bundled the save across all 3 tables — `accounts`, `transactions`, and Outbox — into its own local transaction. If `QuerierFrom` returned `*sql.DB` when there's no ambient transaction, each `ExecContext` would auto-commit individually, silently breaking that atomicity. Deciding the commit responsibility itself via `TxFromContext` avoids this regression.

### Real use case — `TransferHandler` (transferring money between accounts)

The flagship use case that must bundle multiple Repositories (more precisely, two different `Account` instances from the same `AccountRepository`) into a single transaction is transferring money between accounts — if the withdrawal-account save and the deposit-account save each commit independently, a failure mode arises where "the withdrawal was applied but the deposit was lost":

```go
// internal/application/command/transfer_handler.go — actual code
func (h *TransferHandler) Handle(ctx context.Context, cmd TransferCommand) (*TransferResult, error) {
	// ... load source/target, evaluate eligibility, call Withdraw/Deposit ...

	if err := h.tx.RunInTx(ctx, func(ctx context.Context) error {
		if err := h.repo.SaveAccount(ctx, source); err != nil {
			return err
		}
		return h.repo.SaveAccount(ctx, target)
	}); err != nil {
		return nil, err
	}
	// ...
}
```

`main.go` creates `database.NewManager(db)` and injects it as the `command.TransactionManager` port — the Application layer depends only on that port interface, never on the concrete `*database.Manager` (see layer-architecture.md).

---

## Common Entity columns — `created_at` / `updated_at` / `deleted_at`

Both the `accounts` and `transactions` tables in `migrations/0001_init.sql` have these three columns:

```sql
CREATE TABLE accounts (
  id          VARCHAR(36)  PRIMARY KEY,
  owner_id    VARCHAR(36)  NOT NULL,
  amount      BIGINT       NOT NULL DEFAULT 0,
  currency    VARCHAR(3)   NOT NULL DEFAULT 'KRW',
  status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP    NULL
);
```

Since Go has no ORM base class/mixin (this could be mimicked with a shared struct embedding, but this repository doesn't do that), the `Account` struct directly holds `CreatedAt`/`UpdatedAt time.Time` fields, and `DeletedAt` exists as a DB column but isn't yet mapped onto the Go-side `Account` struct (because there's no use case that actually triggers a soft delete — see below).

### The column type is `TIMESTAMP`, so the value must arrive in UTC

These columns are `TIMESTAMP` **without time zone** — Postgres stores the wall-clock digits it is handed and records no offset. `lib/pq` formats a `time.Time` using the location that value carries, so a `time.Now()` reading writes UTC on a UTC CI runner and local time on a developer machine, and the column ends up mixing both. Every Go-side timestamp therefore goes through `common.Now()` (`internal/common/clock.go`), which returns `time.Now().UTC()` — see the timezone rule in [conventions.md](../conventions.md) for what is and is not converted, and the `utc-timestamp-source` harness rule that enforces it on `internal/domain/**` and `internal/infrastructure/persistence/**`.

The columns that default to `CURRENT_TIMESTAMP`, and the `updated_at = NOW()` assignments in the repositories' `ON CONFLICT DO UPDATE` clauses, are evaluated by Postgres rather than by Go, so they follow the **database session's** `TimeZone` setting instead. `postgres:16-alpine` — the image used by both docker-compose and the testcontainers e2e setup — reports `TimeZone = UTC`, so the two sources of a timestamp agree. Setting a `TZ`/`PGTZ` on that container, or pointing the service at a server configured for a local zone, would reintroduce the same mixed-column problem from the database side; leave it on UTC.

---

## Soft delete

- Schema: `deleted_at TIMESTAMP NULL` — `NULL` means active, non-NULL means deleted.
- Query filter: `FindAccounts` in `account_repository.go` includes `WHERE ... deleted_at IS NULL` by default.
- **Known gap**: there's no `DELETE`/soft-delete use case in the code that actually populates `deleted_at`. `Account.Close()` only changes `Status` to `StatusClosed` and never touches `deleted_at` — "closing an account" and "deleting the row" are separate concepts in this domain. If a use case to fully erase an account ever arises in the future (e.g. deleting personal data for regulatory compliance):

```go
func (r *AccountRepository) SoftDelete(ctx context.Context, accountID string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE accounts SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`, accountID)
	return err
}
```

Hard delete (`DELETE FROM accounts ...`) is never used.

---

## Migrations

This uses the approach of running sequentially numbered SQL files directly (plain SQL, no tool like `golang-migrate`):

```
migrations/
  0001_init.sql                            ← creates the accounts, transactions tables
  0001_init.down.sql                       ← reverses 0001_init.sql
  0002_add_email_and_sent_emails.sql       ← adds the accounts.email column + creates the sent_emails table
  0002_add_email_and_sent_emails.down.sql  ← reverses 0002_add_email_and_sent_emails.sql
```

`TestMain` in `test/account_e2e_test.go` reads and runs the up files in order after the container starts (`os.ReadFile(filepath.Join("..", "migrations", migration))`) — since it lists filenames in a hardcoded list, having `.down.sql` files mixed in doesn't affect the up-run path. Every `NNNN_*.sql` has a matching `NNNN_*.down.sql`, which removes the tables/columns/indexes that up file created, in reverse creation order — for example, `0001_init.down.sql` drops `transactions` first (since its foreign key references `accounts`) and then `accounts`. A version-tracking tool like `golang-migrate/migrate` (a `schema_migrations` table) still hasn't been introduced — actually applying a down file is an operator's manual job, and this repository doesn't automate that execution mechanism itself.

Go has no concept corresponding to automatic schema synchronization like `synchronize`/`ddl-auto: update` (`database/sql` isn't an ORM to begin with, so there's no automatic-sync feature at all) — since the schema can only ever change through a migration file, this actually makes it structurally easier to uphold the root principle (migrations must always be used in production). That's why the harness has no rule corresponding to `no-orm-autosync-in-prod-config` — there's simply no ORM auto-sync setting in this stack to check for (see the "Rules not implemented" section of `implementations/go/harness/README.md`).

---

## The soft-delete filter is automatically checked by the harness

A regression where a `Find*`/`FindAll` query targeting a table with a `deleted_at` column (per the migration SQL) omits the `deleted_at IS NULL` filter is automatically checked by `implementations/go/harness/soft_delete_filter.go` (the `soft-delete-filter` rule) — it first determines, from `root/migrations/*.sql` (excluding `.down.sql`), which tables actually have a `deleted_at` column, and then checks textually whether the filter is present in the body of each query method in `internal/infrastructure/persistence/*_repository.go` that targets one of those tables (it doesn't matter whether the filter appears in a static SQL string or as a seed value in a dynamic WHERE-clause builder). Methods targeting a table with no such column at all (currently every table except accounts) are excluded from this check.

### Related documents

- [repository-pattern.md](repository-pattern.md) — separating the Repository interface from its implementation
- [layer-architecture.md](layer-architecture.md) — the Application-layer orchestration that needs transaction propagation
- [domain-events.md](domain-events.md) — why the Outbox table must also be part of the same transaction
- [testing.md](testing.md) — the E2E setup that runs migration files via testcontainers
