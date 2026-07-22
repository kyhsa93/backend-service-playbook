# Layer Architecture (Go)

The principle follows the root [layer-architecture.md](../../../../docs/architecture/layer-architecture.md): `Interface → Application → Domain`, with `Infrastructure` implementing `Domain`'s interfaces to invert the dependency. Since Go has no NestJS-style `@Injectable`/DI container, this repository produces exactly the same dependency direction by **declaring the interface in the domain package, placing the implementation in the infrastructure package, and wiring them together by hand with constructor functions in `cmd/server/main.go`**.

---

## Dependency direction

```
internal/interface/http   →  internal/application/{command,query}  →  internal/domain/account
                                                                            ↑ (interface definition)
                                                              internal/infrastructure/persistence (implementation)
```

Go's `import` graph is the dependency direction itself — `internal/domain/account` never imports `internal/infrastructure/...` (indeed, looking at `account.go`/`repository.go`, there's no import of `database/sql` or `net/http` at all). `internal/infrastructure/persistence/account_repository.go` imports `internal/domain/account` in the opposite direction to implement its interface — this is dependency inversion in Go.

---

## Domain layer — `internal/domain/account/`

Framework-agnostic pure Go code. This can be verified by looking at the imports: `account.go` uses only `time` and `github.com/google/uuid` (the standard library plus a minimal dependency).

- **Aggregate Root** — `Account` (`account.go`): invariants are validated and state is changed only inside domain methods (`Deposit`, `Withdraw`, `Suspend`, `Reactivate`, `Close`).
- **Entity** — `Transaction` (`transaction.go`): identified by `TransactionID`.
- **Value Object** — `Money` (`money.go`): equality determined by `Equals()` on the combination of `Amount`+`Currency`.
- **Domain Event** — `AccountCreated`, etc. (`events.go`): past-tense names, the `DomainEvent` interface.
- **Repository interface** — `Repository` (`repository.go`): only the signature lives here, no implementation.

```go
// internal/domain/account/repository.go — interface only, no implementation
type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	SaveAccount(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

→ See [tactical-ddd.md](tactical-ddd.md) for details.

---

## Application layer — `internal/application/{command,query}/`

Go has no `@nestjs/cqrs`-style `CommandBus`/`QueryBus` — instead, **a struct plus a `Handle` method** plays the same role as the root's Command/Query Service (see [cqrs-pattern.md](cqrs-pattern.md)). The principle that this layer only orchestrates and delegates business logic to the Aggregate is unchanged.

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)  // 1. fetch from Repository
	if err != nil {
		return nil, fmt.Errorf("deposit: %w", err)
	}
	tx, err := a.Deposit(cmd.Amount)                                // 2. delegate to the domain method
	if err != nil {
		return nil, err
	}
	if err := h.repo.SaveAccount(ctx, a); err != nil {               // 3. save via Repository (Outbox row in the same transaction too)
		return nil, err
	}
	// returns immediately after saving — the side effect (notification) is handled
	// asynchronously by the independently-ticking outbox.Poller/outbox.Consumer (see domain-events.md).
	return &tx, nil
}
```

The root document requires separating the Command Service and Query Service into **distinct interfaces** (Repository vs Query). This repository follows that principle by defining `Repository` (Command, includes `SaveAccount`) and `Query` (read-only methods) as separate interfaces in `internal/domain/account/repository.go` — the Query Handlers (`query/get_account_handler.go`, `query/get_transactions_handler.go`) receive only `Query` as a dependency, so at the type-system level they simply cannot call `SaveAccount`:

```go
// internal/domain/account/repository.go
type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}

type Repository interface {
	Query
	SaveAccount(ctx context.Context, account *Account) error
}

// internal/application/query/get_account_handler.go
type GetAccountHandler struct {
	repo account.Query  // depends only on the read-only interface
}
```

`AccountRepository` in `internal/infrastructure/persistence/account_repository.go` doesn't need a separate implementation for each of the two interfaces — since Go interfaces use structural typing, a single concrete struct with the three methods (`FindAccounts`/`FindTransactions`/`Save`, including `Save`) satisfies both `Repository` and `Query` at once. `router.go` still assembles a single `accountRepo` instance and passes it to the Command Handler as `account.Repository` and to the Query Handler as `account.Query` — since `Repository` embeds `Query`, it can be passed without any separate adapter. If the point comes where the read model needs to be split into a separate store (a read replica, cache, search index, etc.), this can be extended by adding a read-only implementation that implements only `Query`.

---

## Interface layer — `internal/interface/http/`

Receives an HTTP request, converts it into a Command/Query, and converts errors into HTTP status codes (`account_handler.go`). The Interface DTO (`dto.go`) implements the root's "thin wrapper around the Application object" principle in a Go-specific way — instead of TypeScript's `class X extends Y {}` inheritance, since Go has no inheritance, it uses an independent struct **that re-declares the fields as-is** and maps them explicitly in the handler:

```go
// dto.go — a thin wrapper around the Application Result (field duplication + mapping instead of inheritance)
type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	Balance   MoneyResponse `json:"balance"`
	// ...
}

// account_handler.go — the handler performs the mapping explicitly
json.NewEncoder(w).Encode(GetAccountResponse{
	AccountID: result.AccountID,
	Balance:   MoneyResponse{Amount: result.Balance.Amount, Currency: result.Balance.Currency},
	// ...
})
```

---

## Infrastructure layer — `internal/infrastructure/`

The only layer that implements Domain's interfaces. `persistence/account_repository.go` enforces interface satisfaction at compile time:

```go
var _ account.Repository = (*AccountRepository)(nil)
```

If this one line fails (even a single method signature mismatch), **the build simply doesn't compile.** What TypeScript's structural typing or the `implements` keyword enforces automatically at the compiler level, Go achieves explicitly through this idiom — none of the root documents mention this mechanism, because in other languages the compiler does this automatically without a separate idiom being needed.

---

## Instead of DI — constructor chaining in `cmd/server/main.go`

The root document defers with "see `docs/implementations/` for the framework-specific DI wiring method." Go's answer is **no DI container, constructor functions called by hand**.

```go
// cmd/server/main.go
db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
// ...
notifier := notification.NewService(notification.NewSESClient(), db)
outboxWriter := outbox.NewWriter()
sqsClient := outbox.NewSQSClient()
outboxHandlers := map[string]outbox.Handler{ /* ... handler per event type ... */ }
outboxPoller := outbox.NewPoller(db, sqsClient, queueURL)         // Outbox → SQS publish (independent goroutine)
outboxConsumer := outbox.NewConsumer(sqsClient, queueURL, outboxHandlers) // SQS → Handler execution (independent goroutine)
accountRepo := persistence.NewAccountRepository(db, outboxWriter) // create the infrastructure implementation
mux := httphandler.NewRouter(accountRepo)                         // inject as the domain interface type
```

```go
// internal/interface/http/router.go — Application layer assembly continues here
func NewRouter(repo account.Repository) *http.ServeMux {
	depositHandler := command.NewDepositHandler(repo)
	getAccountHandler := query.NewGetAccountHandler(repo)
	// ...
}
```

The Command Handler never references `outboxPoller`/`outboxConsumer` at all — it returns immediately after `Repository.Save`, and the Outbox → SQS publish/receive runs independently as separate goroutines started by `main()` (`go outboxPoller.Run(ctx)`, `go outboxConsumer.Run(ctx)`) (see domain-events.md).

The concrete type (`*AccountRepository`) returned by `persistence.NewAccountRepository(db)` implicitly satisfies `NewRouter`'s parameter type (the `account.Repository` interface) — since Go uses structural typing, no `implements` declaration is needed. This repository's approach replaces what a reflection-based DI container does (finding and wiring an implementation by type name) with **a function call the compiler can verify statically**, and as more domains are added, no complexity beyond adding another constructor call to `main.go` accrues.

---

## Go transaction propagation — `context.Context` vs AsyncLocalStorage

The root document recommends context-local storage (Node: AsyncLocalStorage) when bundling multiple Repositories into a single transaction. Go's idiomatic counterpart is propagating it as a value carried on `context.Context`, and `internal/infrastructure/database/` (`WithTx`/`TxFromContext`/`QuerierFrom`/`Manager`) actually implements this — cross-account Transfer, which bundles the withdrawal-account save and the deposit-account save into one transaction, is the real use case. `AccountRepository.SaveAccount()` joins the ambient transaction if one exists, and otherwise (as in previously-existing standalone call sites) opens and commits its own. See [persistence.md](persistence.md) for details.

---

## The dependency direction is automatically checked by the harness

The dependency direction described in this document is statically enforced by two harness rules:

- `domain-layer-isolation` (`implementations/go/harness/domain_layer_isolation.go`) — checks, based on import path segments, that `internal/domain/**/*.go` never imports any of `internal/application/`, `internal/infrastructure/`, `internal/interface/` (since this isn't a blocklist of specific library names, it automatically covers new packages as they're added).
- `interface-no-infrastructure` (`implementations/go/harness/interface_no_infrastructure.go`) — checks that `internal/interface/**/*.go` (HTTP handlers/routers) never imports `internal/infrastructure/` directly. For a technical concern that needs an infrastructure implementation, such as JWT verification, a small interface is declared near where it's used (`interface/http/middleware`, etc.) and received via structural typing, deferring concrete-type assembly to `cmd/server/main.go` (the composition root) — the same pattern as `TokenIssuer`/`PasswordHasher` in `authentication.md` (`middleware.TokenVerifier` follows this same approach).

Even if this dependency direction is accidentally violated while adding a new domain, `harness.sh` catches it as a FAIL.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — internal design of the Domain layer
- [cqrs-pattern.md](cqrs-pattern.md) — the Command/Query Handler pattern
- [repository-pattern.md](repository-pattern.md) — separating the Repository interface from its implementation
- [persistence.md](persistence.md) — the actual current state and gaps of transaction propagation
- [domain-events.md](domain-events.md) — event handling in the Application layer
