# Aggregate ID Generation (Go)

The principle follows the root [aggregate-id.md](../../../../docs/architecture/aggregate-id.md) as-is. The ID is generated server-side in the **Domain layer (Aggregate constructor)**, and the format is a **32-character hex string, a UUID v4 with hyphens removed**.

```go
"550e8400e29b41d4a716446655440000"   // correct — 32 characters, no hyphens
"550e8400-e29b-41d4-a716-446655440000"  // incorrect — contains hyphens
1, 2, 3                                  // incorrect — auto-increment number
```

---

## ID generation utility

The Go standard library has no UUID generator, so a minimal dependency such as `github.com/google/uuid` is used. Hyphen removal is handled directly with `strings.ReplaceAll`.

```go
// internal/common/id.go
package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID returns a 32-character hex string, a UUID v4 with hyphens removed.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
```

---

## `common.NewID()`

`common.NewID()` (32-character hex with hyphens removed) is used everywhere: `New()` in `internal/domain/account/account.go`, `newTransaction()` in `internal/domain/account/transaction.go`, and the outbox/sent_email IDs issued by `internal/infrastructure/outbox/writer.go`/`internal/infrastructure/notification/service.go`.

The column type (`VARCHAR(36)`) can hold either 32 or 36 characters (with hyphens), so no migration was needed for this.

---

## Usage in the Aggregate constructor

Separating new-creation from DB restoration into distinct functions is a Go convention — this repository implements that principle by splitting the constructor `New(...)` from the restoration function `Reconstitute(...)`.

```go
// internal/domain/account/account.go
func New(ownerID, email, currency string) *Account {
	return &Account{
		AccountID: common.NewID(), // new creation — issues a new ID
		OwnerID:   ownerID,
		// ...
	}
}

func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	return &Account{
		AccountID: accountID, // DB restoration — reuses the existing ID as-is
		OwnerID:   ownerID,
		// ...
	}
}
```

- **New creation**: `New()` issues a new ID. It never accepts a client-supplied ID — the fact that ID isn't in `New()`'s parameter list is itself what enforces this rule in code.
- **DB restoration**: `FindAccounts` in `internal/infrastructure/persistence/account_repository.go` passes the `id` column value read from the DB row into `Reconstitute()`. The Repository never issues a new ID.

---

## ID handling in the Repository implementation

The Repository saves the ID the Aggregate already has, as-is. `Save()` in `internal/infrastructure/persistence/account_repository.go`:

```go
_, err = tx.ExecContext(ctx,
	`INSERT INTO accounts (id, owner_id, email, amount, currency, status, updated_at)
	 VALUES ($1, $2, $3, $4, $5, $6, NOW())
	 ON CONFLICT (id) DO UPDATE SET amount = EXCLUDED.amount, status = EXCLUDED.status, updated_at = NOW()`,
	a.AccountID, a.OwnerID, a.Email, a.Balance.Amount, a.Balance.Currency, string(a.Status),
)
```

`a.AccountID` is already a value finalized in the Domain layer — it is never freshly issued by a DB mechanism such as `SERIAL`/`AUTO_INCREMENT` or `RETURNING id`.

---

## Child Entity IDs

`Transaction` (an Entity) uses a string ID just like the Aggregate Root (`newTransaction()` in `internal/domain/account/transaction.go`). Child Entities are also created in the Domain layer, and the Repository or DB is never involved.

---

## The harness automatically checks hyphen removal

A regression where `common.NewID()` returns a UUID v4 as-is without removing hyphens is automatically caught by `implementations/go/harness/aggregate_id_format.go` (the `aggregate-id-format` rule) — it text-searches the `NewID()` implementation in `internal/common/id.go` to confirm it contains hyphen-removal code such as `strings.ReplaceAll(..., "-", "")` (or a method that never produces hyphens in the first place, such as `hex.EncodeToString`), and flags it as FAIL if it returns `uuid.New().String()`/`uuid.NewString()` unprocessed.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the Aggregate constructor pattern, separating `New`/`Reconstitute`
- [repository-pattern.md](repository-pattern.md) — the Repository's save/restore responsibilities
- [persistence.md](persistence.md) — the ID column type (`VARCHAR(36)`) and schema
