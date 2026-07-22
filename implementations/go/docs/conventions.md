# Coding Conventions (Go)

This document takes the framework-agnostic rules from the root [conventions.md](../../../docs/conventions.md) (REST API design, commits/branches, rate-limiting principles, Repository method naming) and makes them concrete for the Go implementation. All grounding code comes from `implementations/go/examples/` (the Account domain) and `implementations/go/docs/architecture/*.md`.

## 1. File naming rules

- Every filename: `snake_case.go`
- Package name: a single lowercase word, no underscores/camelCase, matching the directory name — `package account`, `package persistence`, `package command`
- Aggregate Root: `<aggregate-root>.go` (domain layer) — `account.go`
- Entity: `<entity>.go` (domain layer) — `transaction.go`
- Value Object: `<value-object>.go` (domain layer) — `money.go`
- A status/enum-like Value Object: `<domain>_status.go` — `account_status.go`
- Domain Event: gathered into a single file, `events.go` (at the scale of a single Aggregate with few event kinds, gathering them into one file is more practical than splitting per event. If the number of events grows and the file becomes bloated, splitting into `<event-name>.go` is also an option)
- sentinel errors: gathered into a single file, `errors.go` — the `var ErrXxx = errors.New(...)` declarations
- Repository interface: `repository.go` (domain layer, signature only)
- Repository implementation: `<aggregate>_repository.go` (infrastructure layer) — `account_repository.go`
- CommandHandler: `<verb>_<noun>_handler.go` (placed under `application/command/`) — `create_account_handler.go`, `deposit_handler.go`
- QueryHandler: `<verb>_<noun>_handler.go` (placed under `application/query/`) — `get_account_handler.go`
- Result DTO: `result.go` (placed under `application/query/`, gathering multiple Result structs into one file)
- Technical Service interface (notification, secret, storage, etc.): defined as `<concern>.go` in the application package that uses it — `notifier.go`
- Technical Service implementation: `service.go` under the corresponding concern's sub-package in infrastructure — `notification/service.go`
- HTTP handler: `<domain>_handler.go` (placed under `interface/http/`) — `account_handler.go`
- HTTP DTO: `dto.go` (placed under `interface/http/`)
- Router/assembly: `router.go` (placed under `interface/http/`)
- Scheduler: `<domain>_<concern>_scheduler.go` (infrastructure layer) — `account_cleanup_scheduler.go`
- Task Consumer: `<domain>_task_consumer.go` (interface layer)
- Middleware: `<concern>_middleware.go` (placed under `interface/http/middleware/`) — `auth_middleware.go`, `correlation_middleware.go`
- Config: `<concern>_config.go` (placed under `infrastructure/config/`) — `database_config.go`, `jwt_config.go`
- Shared pure utility: `internal/common/<concern>.go` — `id.go`
- Entry point: `cmd/server/main.go`

---

## 2. Type and identifier naming rules

- Type name (struct/interface): `PascalCase` — `Account`, `Money`, `Repository`
- Public function/method: `PascalCase` — `New`, `Deposit`, `FindAccounts`, `Handle`
- Private function/method: `camelCase` — `newTransaction`, `describe`, `querier`
- Aggregate Root: a domain noun — `Account`
- Entity: a domain noun — `Transaction`
- Value Object: a domain concept — `Money`
- Domain Event: past tense — `AccountCreated`, `MoneyDeposited`, `AccountSuspended`
- sentinel error: `ErrXxx` — `ErrNotFound`, `ErrInsufficientBalance`, `ErrWithdrawRequiresActiveAccount`
- Repository interface: `Repository` (since the package name already conveys the domain, `account.Repository` suffices — it isn't duplicated as `AccountRepository`)
- Repository implementation: `<Aggregate>Repository` — `AccountRepository` (since the package name is `persistence`, this is `persistence.AccountRepository`)
- CommandHandler: `<Verb><Noun>Handler` — `CreateAccountHandler`, `DepositHandler`
- QueryHandler: `<Verb><Noun>Handler` — `GetAccountHandler`, `GetTransactionsHandler`
- Command: `<Verb><Noun>Command` — `CreateAccountCommand`, `DepositCommand`
- Query: `<Verb><Noun>Query` — `GetAccountQuery`, `GetTransactionsQuery`
- Result: `<Verb><Noun>Result` — `GetAccountResult`, `GetTransactionsResult`
- Constructor function: `New<Type>` — `NewAccountRepository`, `NewDepositHandler`, `NewRouter`
- Interface names prefer a role noun over verb+er — `Repository`, `AccountAdapter`, `SecretService` (TypeScript's `<X>Adapter`, `<X>Service` are in the same family as this principle)
- An interface is defined in the package of the side that **uses** it, not the side that satisfies it — `command.PasswordHasher` declares only the minimal signature (`Hash`/`Verify`) the `command` package needs, and the bcrypt-based implementation satisfying it doesn't even need to know this interface exists

---

## 3. Separating the interface from the implementation — compile-time verification

Go has no concept corresponding to NestJS's `abstract class` + DI token registration. The same role is played by declaring an `interface` type in the domain (or the using side's application) package, and putting the implementation struct in the infrastructure package.

```go
// internal/domain/account/repository.go — interface only, no implementation
type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	SaveAccount(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

```go
// internal/infrastructure/persistence/account_repository.go — the implementation
type AccountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) *AccountRepository {
	return &AccountRepository{db: db}
}

// Compile-time interface-satisfaction check — always write this.
// If even one method signature is mismatched, the build fails right here.
var _ account.Repository = (*AccountRepository)(nil)
```

**Rules:**
- Always add `var _ Interface = (*Impl)(nil)` to an implementation of a Repository, Adapter, or Technical Service (PasswordHasher, SecretService, StorageService, etc.).
- Without this one line, the program behaves identically, but it catches at compile time a refactor that accidentally breaks the implementation's interface satisfaction — there's no need to wait until runtime.
- An Application Handler declares its constructor parameter as the interface type (`repo account.Repository`, not the concrete type `*persistence.AccountRepository`). When `main.go`/`router.go` passes a concrete-type value, Go's structural typing automatically checks satisfaction — there's no separate "binding registration" step.

---

## 4. Go typing and domain modeling patterns

### Errors as return values — no exceptions

```go
// correct
func (a *Account) Withdraw(amount int64) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	// ...
}
```

Go has no exceptions. "Throw immediately" is expressed as "immediately `return the zero value, err`." The caller must always check `if err != nil`.

### `context.Context` is always the first argument

```go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) { ... }
func (r *AccountRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) { ... }
```

For every function crossing a layer boundary — Repository, Handler, Adapter, Technical Service, etc. — the first argument is `context.Context`. Cancellation, deadlines, (once implemented) correlation ID, and transactions all propagate explicitly through this argument.

### Aggregate — change state with a pointer receiver

```go
func (a *Account) Deposit(amount int64) (Transaction, error) { ... } // (a *Account) — a pointer receiver, since it changes state
```

### Value Object — preserve immutability with a value receiver

```go
func (m Money) Add(other Money) (Money, error) { ... } // (m Money) — a value receiver, always returns a new value
func (m Money) Equals(other Money) bool { ... }
```

Using a value receiver (`(m Money)`) is deliberate. Using a pointer receiver would let the method mutate the original inside its body, blurring the meaning of an immutable object.

### Separating creation from restoration — `New` / `Reconstitute`

```go
func New(ownerID, email, currency string) *Account { /* issues a new ID, raises events */ }
func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	/* restores only state, with no events */
}
```

The fact that `New(...)`'s parameter list has no ID is itself what enforces the rule "a client-supplied ID is never accepted" in code.

### Nullable — prefer zero value, use a pointer only when needed

Go has no distinction like TypeScript's `T | null` / `T | undefined`. This repository defaults to using the zero value (`""`, `0`, a `nil` slice) as "no value."

```go
// FindAccounts's dynamic filter — the zero value means "no condition"
if q.AccountID != "" {
	where = append(where, fmt.Sprintf("id = $%d", i))
	args = append(args, q.AccountID)
	i++
}
```

A field that needs to distinguish the DB's true NULL from "not set" (e.g. an optional closing date) is expressed explicitly with a pointer (`*time.Time`). Which to use is judged deliberately per field — it's never wrapped in a pointer unconditionally.

### Minimize use of `any`

`any` (i.e. `interface{}`) is reserved for cases that genuinely need to accept an arbitrary type (a JSON payload, etc.). Domain values and Command/Query/Result fields are always declared as a concrete type or a well-defined interface.

### The enum equivalent — `type Status string` + a constant group

```go
// internal/domain/account/account_status.go
type Status string

const (
	StatusActive    Status = "ACTIVE"
	StatusSuspended Status = "SUSPENDED"
	StatusClosed    Status = "CLOSED"
)
```

What corresponds to TypeScript's `enum` is Go's `type X string` + a `const` group. Magic strings are never compared directly — comparisons always go through this constant.

---

## 5. REST API endpoint design rules

The principle is identical to section 1 of the root [conventions.md](../../../docs/conventions.md) — URLs are plural noun resources, and the HTTP method expresses the action.

### URL structure — resource-centric, plural nouns

```
// correct
GET    /accounts                    list accounts
GET    /accounts/{id}                get a single account
POST   /accounts                    open an account
POST   /accounts/{id}/deposit        deposit
POST   /accounts/{id}/withdraw       withdraw
POST   /accounts/{id}/suspend        suspend
POST   /accounts/{id}/reactivate     reactivate
POST   /accounts/{id}/close          close
GET    /accounts/{id}/transactions   view transaction history

// incorrect
GET    /getAccounts        never put a verb in the URL
POST   /createAccount      never put a verb in the URL
GET    /account/{id}       singular form is forbidden — always plural
```

### Routing — `net/http`'s method+path pattern (Go 1.22+)

```go
// internal/interface/http/router.go
mux := http.NewServeMux()
mux.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
mux.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
mux.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
```

Path variables are pulled out via `r.PathValue("id")`. Method+path matching is possible with the standard library alone, with no separate routing framework (gorilla/mux, etc.) needed (Go 1.22+).

### HTTP methods and response codes

| Method | Purpose | Success code | Response body |
|--------|------|----------|----------|
| `GET` | Query a resource | 200 OK | Present |
| `POST` | Create a resource / perform an action | 201 Created | Present (creation result) or absent (an action-style POST) |
| `PUT` | Full update of a resource | 200 OK | Present |
| `PATCH` | Partial update of a resource | 200 OK | Present |
| `DELETE` | Delete a resource | 204 No Content | Absent |

```go
w.WriteHeader(http.StatusCreated)  // POST success
w.WriteHeader(http.StatusNoContent) // success for a state-change-style POST (suspend, close, etc.) — no body
```

### List queries — pagination and filtering

```
GET /accounts?page=0&take=20&status=active
```

```go
// internal/interface/http/account_handler.go
func parsePagination(r *http.Request) (page, take int) {
	page, take = 0, 20
	if v, err := strconv.Atoi(r.URL.Query().Get("page")); err == nil {
		page = v
	}
	if v, err := strconv.Atoi(r.URL.Query().Get("take")); err == nil {
		take = v
	}
	return page, take
}
```

- `page`: starts at 0. `take`: page size.
- Since there's no framework-level automatic DTO binding, it's parsed directly from `r.URL.Query()`.
- Whether a parse failure (an invalid query parameter) is absorbed into the default value or rejected with a 400 is decided deliberately per endpoint — this repository's example chose lenient parsing (absorbing into the default).

### List response — a domain-plural key + count

```go
// internal/interface/http/dto.go
type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"`
	Count        int                          `json:"count"`
}
```

Instead of a generic key like `result`/`data`/`items`, the plural of the domain noun is used. `Count` is not the page size but the total count after filters are applied.

### Single-item response — no generic wrapper

```go
// internal/interface/http/dto.go
type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	OwnerID   string        `json:"ownerId"`
	Balance   MoneyResponse `json:"balance"`
	Status    string        `json:"status"`
	CreatedAt time.Time     `json:"createdAt"`
}
```

No wrapper like `{ success: true, data: {...} }` is applied. Success vs error is distinguished by the HTTP status code.

---

## 6. Method/constructor naming and organization

### CommandHandler / QueryHandler — the common signature

```go
func (h *XxxHandler) Handle(ctx context.Context, cmd/query X) (result, error)
```

Every Handler completes its use case with a single `Handle` method. No runtime routing layer like `@nestjs/cqrs`'s CommandBus/QueryBus is introduced — the Handler is held directly as a field and called (since the type is already fixed at compile time).

### Constructor function — `New<Type>(dependencies...) *Type`

```go
func NewDepositHandler(repo account.Repository) *DepositHandler {
	return &DepositHandler{repo: repo}
}
```

Since there's no DI container, dependencies are always passed explicitly as parameters to a constructor function. When a new dependency is needed, add a constructor parameter and fix up the call site (`main.go`/`router.go`).

### Repository method naming

- List/single-item lookup: `Find<Noun>s(ctx, query) ([]*T, int, error)` — a single item is looked up via the same method by putting an ID filter + `Take: 1` in `query`.
- Save (covers both create and update, upsert): `Save<Noun>(ctx, *T) error` — never leave it as a bare `Save` with no Noun (the method name alone should reveal which Aggregate is being saved, and it collides when a package has multiple Repositories, e.g. the Payment BC's `Repository`/`RefundRepository`)
- An `update<Noun>`-style method is never added — state changes are always applied via `Save` after calling an Aggregate domain method.

Follows the root [conventions.md](../../../docs/conventions.md) section 5's `find<Noun>s` unification rule as-is. The repeated pattern at single-item-lookup call sites (pull out the first result, `ErrNotFound` if none) is extracted into a free function like `FindOne(ctx, q, accountID, ownerID)` in `internal/domain/account/repository.go` and reused — it plays the same role as java/kotlin-springboot's `findAccounts(...).stream().findFirst().orElseThrow(...)`.

### struct member/file organization order

1. Field declarations (struct)
2. Constructor function (`New...`)
3. Exported methods (business logic, queries, etc.)
4. Unexported methods (internal helpers)

---

## 7. Import organization pattern

### 3-group order — auto-sorted by `goimports`

```go
// 1. standard library
import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

// (or standard library + third-party + internal packages separated by blank lines within a single import() block)
import (
	"database/sql"
	"log"
	"os"

	_ "github.com/lib/pq" // 2. third-party (blank import — for driver registration)

	"github.com/example/account-service/internal/infrastructure/persistence" // 3. internal package
	httphandler "github.com/example/account-service/internal/interface/http"
)
```

- **Group order**: standard library → third-party → internal packages. Groups are separated by a blank line.
- **Within a group**: alphabetical order — follow `goimports`'s output exactly (don't mimic the sorted state by hand).
- **Alias**: use only when package names collide (the `http` standard library vs `interface/http`) or the meaning would otherwise be unclear — `httphandler "github.com/example/account-service/internal/interface/http"`.
- **Blank import**: for a case that needs only the side effect, like DB driver registration, write it as `_ "github.com/lib/pq"` and place it in the third-party group.
- Go has no problem corresponding to TypeScript's `@/` alias or relative-import issues at all — the module path (`github.com/example/account-service/...`) is always an absolute path. The one compiler-enforced rule is that `internal/` can't be imported from outside its parent directory.
- A Domain layer file never imports `internal/infrastructure/...`, `internal/interface/...`, or `internal/application/...` — since the import graph is the dependency direction itself, violating this rule usually leads to a circular dependency, making `go build` fail immediately.

---

## 8. Error handling pattern

### Typed as sentinel errors

```go
// internal/domain/account/errors.go
var (
	ErrNotFound                      = errors.New("account not found")
	ErrInvalidAmount                 = errors.New("amount must be greater than zero")
	ErrWithdrawRequiresActiveAccount = errors.New("account must be active to withdraw")
	ErrInsufficientBalance           = errors.New("insufficient balance")
)
```

A single sentinel error variable plays the combined role of TypeScript's two enums, `<Domain>ErrorMessage`/`<Domain>ErrorCode`. `errors.Is(err, account.ErrNotFound)` compares the variable's identity, so a mapping can never break due to a typo in a string.

- Messages start lowercase and carry no trailing punctuation (standard Go convention, so the message reads naturally even when concatenated after another error message).

### Wrapping with `%w` — adding context

```go
a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
if err != nil {
	return nil, fmt.Errorf("deposit: %w", err)
}
```

- An already-sentinel error isn't rewrapped unnecessarily.
- An "error of unknown cause" such as a DB driver error is wrapped with `fmt.Errorf("<operation description>: %w", err)` so it's traceable which layer's which operation failed.

### Interface layer — mapping to HTTP status codes with `errors.Is`

```go
func writeAccountError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, account.ErrInvalidAmount),
		errors.Is(err, account.ErrInsufficientBalance):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

The responsibility of converting an error into an HTTP status code lives only in the Interface layer. An error not in the mapping is treated as a 500 + a generic message, preventing internal implementation details from leaking.

---

## 9. Logger pattern

### `log/slog` — structured logging

```go
slog.InfoContext(ctx, "notification email sent",
	"event_type", eventType,
	"recipient", content.recipient,
	"ses_message_id", messageID,
)

slog.ErrorContext(ctx, "notification email failed",
	"event_type", eventType,
	"error", err,
)
```

- Field keys are written explicitly in snake_case (`event_type`, `ses_message_id`) — Go code itself conventionally uses camelCase, but since a log field key is a string literal, it can follow the external-monitoring-integration convention (snake_case) as-is.
- Use `InfoContext`/`ErrorContext`, which take a `ctx`, to connect naturally with context values such as the correlation ID.
- The production environment sets up JSON output via `slog.NewJSONHandler`.

### Logging criteria by layer

- Domain layer: never logs. `internal/domain/account/` must have no `log`/`slog` import at all.
- Application layer: logs business events and results of external calls.
- Infrastructure layer: logs external integration failures/retries.
- Interface layer: logs request-handling results and unexpected errors (500).

---

## 10. Comment style

- **Attach a Go doc comment to every exported identifier** — a `//` comment starting with the identifier's name.

```go
// NewID returns a 32-character hex string, a UUID v4 with hyphens removed.
func NewID() string { ... }

// Repository defines the persistence boundary of the Account Aggregate.
type Repository interface { ... }
```

- **Business logic explanations are written as inline `//` comments** — in the team's primary language (Korean).
- There's no separate documentation format like JSDoc — the Go doc comment itself is the documentation-generation standard for `go doc`/pkg.go.dev, so it's followed as-is (this differs in flavor from NestJS's "no JSDoc, `//` only" rule — since Go's tool chain presupposes doc comments, they must always be written for exported identifiers).
- A long Handler method may use section comments for logical separation:

```go
// 1. fetch from Repository
a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
// ...
// 2. delegate to the domain method
tx, err := a.Deposit(cmd.Amount)
```

---

## 11. Commit message convention

Follows the [Conventional Commits](https://www.conventionalcommits.org/) spec. Since this isn't language-dependent, it's identical to section 2 of the root [conventions.md](../../../docs/conventions.md).

### Message structure

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type list

| type | Description | Example |
|------|------|------|
| `feat` | Adds a new feature | `feat(account): add account suspend feature` |
| `fix` | Fixes a bug | `fix(account): fix balance calculation error` |
| `refactor` | Restructures code with no behavior change | `refactor(account): clean up Repository lookup logic` |
| `docs` | Documentation-only change | `docs: update repository-pattern doc` |
| `test` | Adds or modifies tests | `test(account): add withdrawal invariant unit tests` |
| `chore` | Non-code work such as build, CI, dependencies | `chore(deps): bump google/uuid version` |
| `style` | A formatting change with no behavioral effect | `style: apply gofmt` |
| `perf` | Performance improvement | `perf(account): use an index for the list-lookup query` |

### scope rules

- The scope uses the service domain name: `account`, `user`, `payment`, etc.
- For a change spanning multiple domains, omit the scope or use a higher-level concept.
- For a non-code change, use its target as the scope: `ci`, `deps`, `docker`, etc.

### description rules

- Written in English.
- Written in descriptive form, not imperative: "add", "fix", "remove" (NOT "please add", "you should fix").
- Does not start with a capital letter.
- No trailing period.

### BREAKING CHANGE

```
feat(account)!: change the account response schema

BREAKING CHANGE: GetAccountResponse's balance changed from a string to an object
```

### Examples

```
feat(account): add account suspend feature

fix(account): fix a race condition where concurrent withdrawals make the balance negative (#42)

refactor(account): move FindByID's owner-check logic into the Repository

Moved the logic that used to compare ownership directly in the Handler into
a Repository query condition, reducing information exposure on an
unauthorized account lookup.

test(account): add a test for the currency-mismatch error case on withdrawal
```

---

## 12. Branch and PR convention

Since this isn't language-dependent, it's identical to section 3 of the root [conventions.md](../../../docs/conventions.md).

### Branch naming — Conventional Branch

```
<type>/<scope>-<short-description>
```

| type | Purpose | Example |
|------|------|------|
| `feat` | New feature development | `feat/account-suspend` |
| `fix` | Bug fix | `fix/account-balance-race` |
| `refactor` | Refactoring | `refactor/account-repository-query` |
| `docs` | Documentation change | `docs/go-repository-pattern` |
| `test` | Test addition/modification | `test/account-withdraw-invariant` |
| `chore` | Build, CI, dependencies | `chore/ci-go-test-workflow` |

**Rules:**
- Every word is written in `kebab-case`.
- Branches from `main`.
- Never commits/pushes directly to the `main` branch.

### PR workflow

```
1. Create a new branch from main
   git checkout main && git pull origin main
   git checkout -b <type>/<scope>-<short-description>

2. Commit after working (Conventional Commits format)
   git add <files>
   git commit -m "<type>(<scope>): <description>"

3. Push to the remote
   git push -u origin <branch-name>

4. Create a PR to the main branch
   gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR title/body

```
feat(account): add account suspend feature
```

```markdown
## Summary
- Summarize the change in 1-3 lines

## Test plan
- [ ] go test ./internal/... passes
- [ ] go test ./test/... (testcontainers) passes
- [ ] ./harness.sh . passes
```

### Merge strategy

- **Squash and merge** is the default.
- The remote branch is automatically deleted after merge.

---

## 13. Testing pattern

### Unit tests — Domain layer (table-driven)

Domain-layer unit tests are written with no framework, using the pure Go `testing` package, and test only through the public API via an external test package (`package account_test`).

```go
// internal/domain/account/account_test.go
package account_test

import (
	"errors"
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestAccount_Withdraw(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *account.Account
		amount  int64
		wantErr error
	}{
		{
			name:    "withdraw_from_suspended_account_errors",
			setup:   func() *account.Account { a := account.New("owner-1", "a@example.com", "KRW"); _ = a.Suspend(); return a },
			amount:  1000,
			wantErr: account.ErrWithdrawRequiresActiveAccount,
		},
		{
			name:    "withdraw_more_than_balance_errors",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			amount:  1000,
			wantErr: account.ErrInsufficientBalance,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := tt.setup()
			_, err := a.Withdraw(tt.amount)
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Withdraw() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}
```

### Unit tests — Application Handler (manual stub)

```go
// internal/application/command/deposit_handler_test.go
package command_test

type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	a, err := s.findByIDFn(ctx, q.AccountID, q.OwnerID)
	if err != nil || a == nil {
		return nil, 0, err
	}
	return []*account.Account{a}, 1, nil
}
func (s *stubRepository) SaveAccount(ctx context.Context, a *account.Account) error { return s.saveFn(ctx, a) }
// fill in the remaining interface methods, such as FindTransactions, with a minimal implementation too

func TestDepositHandler_Handle_AccountNotFound(t *testing.T) {
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewDepositHandler(repo)

	_, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: "missing", Amount: 1000})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}
```

- The Repository is always mocked as the interface type — mocking a concrete type is forbidden.
- When an interface is small, on the order of 3-4 methods, the Go community generally prefers a hand-written stub over a mocking framework, since it's easier to read/debug. Something like `testify/mock` can be used, but it isn't required.
- Business logic (balance calculation, state-transition validation) has already been verified in the Domain unit tests, so it isn't repeated here — this only checks that the Handler calls the Repository in the correct order. Since the Handler returns immediately after Repository.Save, verifying Outbox publish/consume isn't in scope for this test (that's the responsibility of the independently-ticking `outbox.Poller`/`outbox.Consumer`, see domain-events.md).

### E2E tests — testcontainers-go

```go
// test/account_e2e_test.go
func TestMain(m *testing.M) {
	os.Exit(runTests(m))
}

func runTests(m *testing.M) int {
	ctx := context.Background()
	pgContainer, err := postgres.Run(ctx, "postgres:16-alpine",
		postgres.WithDatabase("account_test"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
	)
	// ... start the LocalStack container, run migrations/*.sql in order ...
	return m.Run()
}
```

- Uses a container instead of a production DB (testcontainers-go starts Postgres + LocalStack directly).
- The container is started only once in `TestMain` and shared across multiple tests — to keep test data isolated, each test generates its own unique `ownerID`/`AccountID` to avoid collisions.
- Passes through the real path from the HTTP request down to DB/external integration, with no bypass.

### Test file placement

```
internal/
  domain/account/
    account_test.go              ← Domain unit test (package account_test, next to the source)
  application/command/
    deposit_handler_test.go      ← Application unit test (package command_test, next to the source)
test/
  account_e2e_test.go            ← E2E test (separate directory, test/ at the module root)
```

Go convention is to place unit tests in the same directory as the source (automatically excluded from `go build` purely by the `_test.go` suffix). Only E2E, which assembles multiple packages and even starts containers, is separated into its own `test/` directory.

### Test naming pattern

```
TestXxx_When<condition>_Then<expected result>
e.g. TestDepositHandler_Handle_AccountNotFound
    TestAccount_Withdraw (states the condition/expected result in the subtest name: "withdraw_from_suspended_account_errors")
```

### Running

```bash
go test ./internal/...        # Domain + Application unit tests (fast, no external dependency)
go test ./test/...            # E2E (requires Docker, testcontainers-go starts the containers itself)
go test ./...                 # everything
```
