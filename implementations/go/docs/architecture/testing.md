# Testing Strategy (Go)

The principle follows the root [testing.md](../../../../docs/architecture/testing.md): 3 tiers — Domain unit tests, Application unit tests (Repository mocks), and E2E tests. All 3 tiers are implemented under `examples/`. This document presents how each layer is written using the Go standard library's `testing` package and the table-driven test idiom.

| Layer | Verification scope | Dependency strategy | Current state |
|---|---|---|---|
| Domain unit tests | Aggregate, Value Object | No framework, pure function calls | **Present** — `internal/domain/account/account_test.go`, `money_test.go` |
| Application unit tests | Command/Query Handler | Repository replaced with a manual stub (`stub_test.go`) | **Present** — `create_account_handler_test.go`, `deposit_handler_test.go` |
| E2E tests | HTTP → Handler → Repository → real DB | testcontainers-go (Postgres + LocalStack) | **Present** — `test/account_e2e_test.go`, `test/notification_e2e_test.go` |

---

## Domain unit tests — actual code

Following standard Go convention, these live as `_test.go` files next to the source (`internal/domain/account/account_test.go`). Since there's no external dependency, `go test ./internal/domain/...` finishes in milliseconds. **Table-driven tests** are Go's standard style — instead of `describe`/`it`, a `[]struct{...}` slice + a `for` loop + `t.Run` subtests are used.

```go
// internal/domain/account/account_test.go — actual code
package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestAccount_Withdraw(t *testing.T) {
	tests := []struct {
		name       string
		setup      func() *account.Account
		amount     int64
		wantErr    error
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
		{
			name:    "withdraw_zero_or_less_errors",
			setup:   func() *account.Account { a := account.New("owner-1", "a@example.com", "KRW"); _, _ = a.Deposit(5000); return a },
			amount:  0,
			wantErr: account.ErrInvalidAmount,
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

func TestAccount_Deposit_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents() // New() has already accumulated an AccountCreated event, so this resets it, leaving only the event under test

	if _, err := a.Deposit(1000); err != nil {
		t.Fatalf("Deposit() unexpected error: %v", err)
	}

	events := a.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if _, ok := events[0].(account.MoneyDeposited); !ok {
		t.Fatalf("want MoneyDeposited, got %T", events[0])
	}
}
```

**Principles:**
- Using `package account_test` (an external test package) tests only through the public API — this naturally verifies that the test doesn't depend on the package's private internal fields.
- Test fixtures are built as a `setup func() *account.Account` closure, assembling only the state that's needed (the same intent as the root's `createOrder(overrides)` helper pattern).
- Expected errors are compared with `errors.Is` (comparing the sentinel error value itself — string comparison is forbidden).
- Only pure logic is verified, with no `math/rand`, time, or external package imports.

---

## Application unit tests — actual code

Replaces the Repository with a mock to verify only the Handler's orchestration logic (error propagation, whether Save was called, whether notify was called). Go doesn't require a mocking framework — writing a minimal stub struct (`stub_test.go`) that implements the `account.Repository` interface by hand is sufficient.

```go
// internal/application/command/deposit_handler_test.go — actual code
package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

// stubRepository is a minimal mock that's injected, per test, with only the
// behavior it needs, as function fields. Since findByIDFn only needs to mimic
// the single-item lookup scenario wrapped by account.FindOne, FindAccounts
// wraps it as a single-item result ([]*account.Account of length 0 or 1) and returns that.
type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	a, err := s.findByIDFn(ctx, q.AccountID, q.OwnerID)
	if err != nil {
		return nil, 0, err
	}
	if a == nil {
		return nil, 0, nil
	}
	return []*account.Account{a}, 1, nil
}
func (s *stubRepository) SaveAccount(ctx context.Context, a *account.Account) error { return s.saveFn(ctx, a) }
func (s *stubRepository) FindTransactions(ctx context.Context, accountID string, page, take int) ([]account.Transaction, int, error) {
	return nil, 0, nil
}

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

func TestDepositHandler_Handle_Saves(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn:     func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewDepositHandler(repo)

	if _, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: a.AccountID, Amount: 1000}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !saveCalled {
		t.Fatal("want repo.Save to be called")
	}
}
```

Since `go.mod` already includes `github.com/stretchr/testify` (used as a `require` package in E2E), the stub could be replaced with `testify/mock` if needed — but when an interface is small, on the order of 3-4 methods, the Go community generally prefers a hand-written stub like the one above for being easier to read and debug.

**Principles:**
- The Repository is always mocked as the interface type (`account.Repository`) — mocking a concrete type is forbidden (same as the root principle).
- Business logic (balance calculation, state-transition validation) has already been verified in the Domain unit tests, so it isn't repeated here — this only checks that the Handler **calls the Repository in the right order**. Since the Handler returns immediately after Repository.Save, verifying Outbox publish/consume isn't in scope for this test (that's the responsibility of the independently-ticking `outbox.Poller`/`outbox.Consumer`, see domain-events.md).

---

## E2E tests

`test/account_e2e_test.go` uses testcontainers-go to start real Postgres + LocalStack (SES) containers, and sends real HTTP requests via `httptest.Server` to verify the full path.

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
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(60*time.Second),
		),
	)
	// ... the LocalStack container is started similarly ...
	// ... migrations/*.sql are read and run in order ...
	return m.Run()
}
```

`test/notification_e2e_test.go` calls LocalStack's SES debug endpoint (`/_aws/ses`) to verify that an email was actually "sent," and even that a record was left in the `sent_emails` table — passing through the real path from the HTTP layer down to the infrastructure layer with no bypass.

**Confirming principle compliance:**
- Uses a container rather than a production DB — matches the root principle.
- The container is started only once in `TestMain` and shared across multiple tests — offsetting the container-startup cost. Still, to keep test data isolated, each test generates its own unique `ownerID`/`AccountID` to avoid collisions (the `ownerID`, `otherOwnerID` constants at the top of the file, and the pattern of creating a fresh account in each test).

---

## Test file placement

```
internal/
  domain/account/
    account_test.go              ← Domain unit test (package account_test, next to the source)
  application/command/
    deposit_handler_test.go      ← Application unit test (package command_test, next to the source)
test/
  account_e2e_test.go            ← E2E test (separate directory, test/ at the module root)
  notification_e2e_test.go
```

Go convention is to place unit tests in the same directory as the source (excluded from `go build` purely by the `_test.go` suffix) — the same placement principle as the root document's example of putting `order.spec.ts` next to the source. Only tests like E2E, which assemble multiple packages and even start containers, are separated into their own `test/` directory.

---

## Running tests

```bash
go test ./internal/...        # Domain + Application unit tests (fast, no external dependency)
go test ./test/...            # E2E (requires Docker, testcontainers-go starts the containers itself)
go test ./...                 # everything
```

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — what Domain unit tests target (Aggregate invariants)
- [layer-architecture.md](layer-architecture.md) — the Application layer's orchestration logic (the unit test target)
- [repository-pattern.md](repository-pattern.md) — the interface that gets mocked
- [local-dev.md](local-dev.md) — the same Postgres/LocalStack setup E2E uses
