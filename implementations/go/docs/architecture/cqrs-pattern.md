# CQRS Pattern (Go)

The principle of CQRS (Command Query Responsibility Segregation) follows the root [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md): separate the responsibility of writes (Command) and reads (Query). Go has no framework equivalent to `@nestjs/cqrs`'s `CommandBus`/`QueryBus` — this repository implements a lightweight CQRS with no such infrastructure, using only a **plain struct + a `Handle` method**, and this repository's actual code is itself the reference for this pattern.

---

## When to apply

| Situation | Recommendation |
|---|---|
| When you want to clearly separate concerns with a dedicated Handler per use case | This document's Handler pattern |
| When there are enough dynamic use cases to need runtime routing via a Command Bus/Query Bus | Uncommon in Go — see "Why no Bus is needed" below |

---

## Directory structure (this repository's actual structure)

```
internal/
  domain/
    account/
      account.go              ← Aggregate Root
      repository.go            ← Repository (Command) + Query interface
      events.go                ← Domain Event
  application/
    command/
      create_account_handler.go    ← CreateAccountCommand + CreateAccountHandler
      deposit_handler.go
      withdraw_handler.go
      suspend_account_handler.go
      reactivate_account_handler.go
      close_account_handler.go
    query/
      get_account_handler.go        ← GetAccountQuery + GetAccountHandler
      get_transactions_handler.go
      result.go                     ← Result structs
    event/
      account_created_event_handler.go   ← event handler run by the Outbox Consumer (see domain-events.md)
      money_deposited_event_handler.go
      money_withdrawn_event_handler.go
      account_suspended_event_handler.go
      account_reactivated_event_handler.go
      account_closed_event_handler.go
  interface/
    http/
      account_handler.go            ← HTTP entry point — calls the Command/Query Handler directly
      router.go                     ← assembly + routing
```

---

## Command and CommandHandler

A Command is an immutable data struct representing a write request. A CommandHandler holds the Repository (and a Technical Service if needed — `PasswordHasher`, `AccountAdapter`, etc.) as a dependency and completes the use case with a single `Handle` method.

```go
// internal/application/command/create_account_handler.go
type CreateAccountCommand struct {
	RequesterID string
	Email       string
	Currency    string
}

type CreateAccountHandler struct {
	repo account.Repository
}

func NewCreateAccountHandler(repo account.Repository) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency) // business logic delegated to the Aggregate
	if err := h.repo.SaveAccount(ctx, a); err != nil {          // save the Aggregate + record the Outbox row, one transaction
		return nil, err
	}
	// returns immediately after saving — Outbox → SQS publish/consume is solely the
	// responsibility of the independently-ticking outbox.Poller/outbox.Consumer
	// (synchronous draining is prohibited, see domain-events.md).
	return a, nil
}
```

The same pattern repeats in `deposit_handler.go`, `withdraw_handler.go`, `suspend_account_handler.go`, `reactivate_account_handler.go`, `close_account_handler.go` — each Handler is responsible for exactly one use case.

**The common signature across every CommandHandler:**

```go
func (h *XxxHandler) Handle(ctx context.Context, cmd XxxCommand) (result, error)
```

Always taking `context.Context` as the first argument is standard Go convention — it's the channel for cancellation propagation, timeouts, and (in the future) transaction/correlation-ID propagation.

---

## Query and QueryHandler

```go
// internal/application/query/get_account_handler.go
type GetAccountQuery struct {
	AccountID   string
	RequesterID string
}

type GetAccountHandler struct {
	repo account.Query
}

func NewGetAccountHandler(repo account.Query) *GetAccountHandler {
	return &GetAccountHandler{repo: repo}
}

func (h *GetAccountHandler) Handle(ctx context.Context, q GetAccountQuery) (*GetAccountResult, error) {
	a, err := account.FindOne(ctx, h.repo, q.AccountID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return &GetAccountResult{ /* Aggregate → Result mapping */ }, nil
}
```

### Mapping to the root document — a separate read-only interface (`account.Query`)

The root [layer-architecture.md](../../../../docs/architecture/layer-architecture.md) specifies that the Query Service use a read-only interface separate from Command. This repository expresses that as two interfaces in `internal/domain/account/repository.go`:

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
```

`GetAccountHandler`/`GetTransactionsHandler` receive only `account.Query` as a dependency — at the type-system level, they cannot call `SaveAccount`. `AccountRepository` in `internal/infrastructure/persistence/account_repository.go` doesn't need to implement the two interfaces separately: since Go interfaces are structurally typed, a single concrete struct with the three methods `FindAccounts`/`FindTransactions`/`SaveAccount` satisfies both `Repository` and `Query` at once (a `var _ account.Query = (*AccountRepository)(nil)` compile-time check is also kept alongside). `internal/interface/http/router.go` still assembles a single `accountRepo` instance and passes it to the Command Handler as `account.Repository` and to the Query Handler as `account.Query` — since `Repository` embeds `Query`, it can be passed as-is with no separate adapter.

If the point comes where the read model needs to be split into a separate store (a read replica, cache, search index, etc.), this can be extended by adding a separate read-only implementation under `internal/infrastructure/` that implements only `Query` — since the interfaces are already separated, the Query Handler side's code doesn't need to change.

---

## Interface layer — calls the Handler directly (no Bus)

```go
// internal/interface/http/account_handler.go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	// ...
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{ /* ... */ })
	// ...
}
```

### Why no Bus is needed

A layer that looks up the handler at runtime by command type, like `CommandBus.execute(command)`, is useful in dynamic languages/frameworks that rely on reflection or decorator metadata. In Go:

- **The exact type is known at compile time** — holding the Handler directly as a field, as in `h.createAccount.Handle(ctx, cmd)`, lets the compiler verify the type. Going through a Bus would instead introduce `interface{}`/reflection, actually reducing type safety.
- **It's already assembled via constructors in `main.go`** — no service locator is needed to find the Handler instance (see the "No DI container" section of [layer-architecture.md](layer-architecture.md)).

So `internal/interface/http/router.go` assembles each Handler directly via its constructor instead of a Bus, and `AccountHandler` holds them as fields:

```go
// internal/interface/http/router.go
func NewRouter(repo account.Repository) *http.ServeMux {
	createAccountHandler := command.NewCreateAccountHandler(repo)
	depositHandler := command.NewDepositHandler(repo)
	// ...
	getAccountHandler := query.NewGetAccountHandler(repo)
	// ...

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler, /* ... */)
	// ...
}
```

---

## EventHandler — Domain Event follow-up processing

This repository implements the Outbox-based EventHandler the root document requires as-is: `Save` in `internal/infrastructure/persistence/account_repository.go` commits the Aggregate state and the Outbox row in the same transaction. The Command Handler returns immediately after saving and never drains the Outbox synchronously — `outbox.Poller` runs independently on its own tick, publishing from Outbox → SQS, and `outbox.Consumer` waits on SQS and delegates the follow-up processing per `event_type` to `internal/application/event/*_event_handler.go`. Details are covered in [domain-events.md](domain-events.md).

```go
// internal/application/event/account_created_event_handler.go
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt) // outbox.Consumer calls this Handle for every message with event_type="AccountCreated"
}
```

---

## Comparison with the traditional architecture

| | Traditional architecture (separate Services) | This repository's Handler-based CQRS |
|---|---|---|
| Write entry point | `XxxCommandService.method()` | `XxxHandler.Handle(ctx, cmd)` |
| Read entry point | `XxxQueryService.method()` | `XxxHandler.Handle(ctx, query)` |
| Routing | Direct Service call | Handler held as a field, called directly (no Bus) |
| Use-case unit | Service method | Handler struct (1 file = 1 use case) |
| Read/write separation | Separate Service classes | Separate Handlers + separate `Repository`/`Query` interfaces |

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction, DI-free assembly
- [domain-events.md](domain-events.md) — EventHandler, the Outbox pattern (Writer/Poller/Consumer)
- [repository-pattern.md](repository-pattern.md) — Repository interface design
- [directory-structure.md](directory-structure.md) — placement of `application/command`/`application/query`
