# Repository Pattern (Go)

The principle follows the root [repository-pattern.md](../../../../docs/architecture/repository-pattern.md): one interface + one implementation per Aggregate Root, with the interface in the domain layer and the implementation in the infrastructure layer. Where TypeScript uses an `abstract class` as the DI token, Go uses the **`interface` type in the domain package** directly — there is no separate token concept. The gist that used to live in `guide.md` has moved into this document and been expanded against the actual code.

---

## Interface location — `internal/domain/account/repository.go`

```go
package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	SaveAccount(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

---

## Implementation location — `internal/infrastructure/persistence/account_repository.go`

```go
type AccountRepository struct {
	db *sql.DB
}

// Compile-time interface-satisfaction check — this one line gives Go, explicitly,
// what TypeScript/Java's `implements` keyword gets automatically at the compiler level.
var _ account.Repository = (*AccountRepository)(nil)

func NewAccountRepository(db *sql.DB) *AccountRepository {
	return &AccountRepository{db: db}
}
```

Removing `var _ account.Repository = (*AccountRepository)(nil)` doesn't change the program's behavior, but it catches a refactor that accidentally breaks `AccountRepository`'s interface satisfaction (e.g. a method signature change) right there as a compile error — there's no need to wait until runtime.

---

## Instead of "DI binding" — injected as the interface type

The root document says "the Application Service is injected with the Repository as the abstract class type." In Go, the same effect is achieved by declaring the constructor function's parameter type as the interface — there is no separate "binding registration" step.

```go
// internal/application/command/deposit_handler.go
type DepositHandler struct {
	repo account.Repository  // an interface type, not a concrete type
}

func NewDepositHandler(repo account.Repository) *DepositHandler {
	return &DepositHandler{repo: repo}
}
```

When `main.go` passes `persistence.NewAccountRepository(db, outboxWriter)` (the concrete type `*persistence.AccountRepository`), the Go compiler checks whether it satisfies the `account.Repository` interface structurally, right at that call site. NestJS concepts like `@Inject(OrderRepository)` token registration or a providers array aren't needed at all.

---

## Method naming — mapping to the root rules

The root document says to use only three patterns: `find<Noun>s` (always returns a list; a single item is mimicked with `take:1`) / `save<Noun>` / `delete<Noun>`. This repository's `account`/`card`/`payment` Repositories follow that rule exactly — a Command-only method must always be named `Save<Noun>` (`SaveAccount`/`SaveCard`/`SavePayment`/`SaveRefund`). Naming it plain `Save` (without a Noun) makes it impossible to tell which Aggregate is being saved from the method name alone, and it collides once multiple Repositories exist in the same package (e.g. the Payment BC's `Repository`/`RefundRepository`):

| Root rule | This repository's actual method | Mapping |
|---|---|---|
| `find<Noun>s` (single item as a list too) | `FindAccounts(ctx, FindQuery)` / `FindCards(ctx, FindQuery)` / `FindPayments(ctx, FindQuery)` | Identical — there's exactly one query method per domain. `FindQuery.Take`/filter fields express both list and single-item lookups, and no separate single-item-only method (e.g. `FindByID`) exists |
| `delete<Noun>` | none | `Account` only changes state via `Close()` and never actually deletes a row — given the nature of the account domain, there's no "delete" use case at all |
| `save<Noun>` | `SaveAccount` / `SaveCard` / `SavePayment` / `SaveRefund` | Identical — upsert (`ON CONFLICT ... DO UPDATE`) covers both create and update |

**A single-item lookup isn't a separate method — it's reused by calling `FindAccounts` with `Take: 1`.** The helper playing the same role as java/kotlin-springboot's `findAccounts(...).stream().findFirst().orElseThrow(...)` is `FindOne(ctx, q, accountID, ownerID)` in `internal/domain/account/repository.go` — it calls `FindAccounts` with `FindQuery{AccountID: accountID, OwnerID: ownerID, Take: 1}` and returns `ErrNotFound` if there's no result. Since Go has no Stream, this repeated pattern (pull out the first result, error if there is none) is extracted into a free function so every call site (Command/Query Handlers, `acl/account_adapter.go`) reuses it without duplication:

```go
// internal/application/command/deposit_handler.go
a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
if err != nil {
	return nil, fmt.Errorf("deposit: %w", err)
}
```

The absence of a separate update method on `Repository` matches the root principle — state changes are always applied by calling an Aggregate method (`Deposit`, `Suspend`, etc.) and then `Save()`.

This method-naming rule is automatically checked by `implementations/go/harness/repository_naming.go` (the `repository-naming` rule) — it flags FAIL if a `domain/`-layer Repository/Query interface has a `FindByID`-style method (`FindBy*`), a bare `FindAll`/`Save`/`Delete`, or a separate `Count*`/`Update*` method. Even if this convention is violated while adding a new domain by hand, `harness.sh` won't silently let it pass.

---

## Dynamic filters — conditional WHERE in `FindAccounts`

The root document's "add a condition only when the value is present" pattern is implemented in Go via slice appends (`account_repository.go`):

```go
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
	// ... whereClause is inserted into the SQL via fmt.Sprintf, args is passed as QueryContext's variadic arguments
}
```

Points to watch: the `$N` placeholder number (`i`) must be incremented every time a condition is added, and while the `WHERE` clause string itself is assembled with `fmt.Sprintf`, **values are never inserted directly into the string** — they are always parameter-bound through `args`. Conflating "composing column names/clause structure" with "binding user input values" leads directly to SQL injection.

---

## Soft delete — filtering on lookup

Every query in `internal/infrastructure/persistence/account_repository.go` includes `deleted_at IS NULL` as a base condition (the initial value of `FindAccounts`'s `where` slice). See [persistence.md](persistence.md) for the detailed schema and deletion flow — though in this example there's no use case that physically deletes an Account (an account only transitions state via `Close()`), so even though the `deleted_at` column exists, there's currently no path that actually populates it.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root design and its relationship to the Repository
- [layer-architecture.md](layer-architecture.md) — layer dependency direction, interface location
- [persistence.md](persistence.md) — transactions, soft delete, migrations
- [domain-events.md](domain-events.md) — where the Repository's `Save<Noun>` saves events into the Outbox within the same transaction
- [aggregate-id.md](aggregate-id.md) — the principle that the Repository never issues a new ID
