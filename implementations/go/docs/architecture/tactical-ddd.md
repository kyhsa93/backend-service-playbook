# Tactical Design (Go) — Aggregate, Entity, Value Object, Domain Event

The principle follows the root [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md). Go has no classes — everything is expressed as **a struct plus methods with that struct as the receiver**. The `internal/domain/account/` package already faithfully implements all four concepts (Aggregate Root, Entity, Value Object, Domain Event). This document explains each concept grounded in that code, and clearly points out Go's own constraint (the absence of true encapsulation).

---

## Aggregate Root — `Account` (`internal/domain/account/account.go`)

```go
type Account struct {
	AccountID          string
	OwnerID            string
	Email              string
	Balance            Money
	Status             Status
	CreatedAt          time.Time
	UpdatedAt          time.Time
	LastInterestPaidAt time.Time     // last date the interest batch actually paid interest (see scheduling.md)
	events             []DomainEvent // starts lowercase — not directly accessible from outside the package
	transactions       []Transaction // starts lowercase — same
}
```

- **Invariants are validated only inside domain methods.** `Deposit`, `Withdraw`, `Suspend`, `Reactivate`, `Close` are the only paths that change state.

```go
// referenceID is empty for a user-requested withdrawal, or the Payment BC's
// paymentId when reacting to payment.completed.v1; merchantName is the
// optional payee/memo used for asynchronous categorization.
func (a *Account) Withdraw(amount int64, referenceID, merchantName string) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	if amount <= 0 {
		return Transaction{}, ErrInvalidAmount
	}
	money, err := NewMoney(amount, a.Balance.Currency)
	if err != nil {
		return Transaction{}, err
	}
	if a.Balance.LessThan(money) {
		return Transaction{}, ErrInsufficientBalance
	}
	newBalance, err := a.Balance.Subtract(money)
	if err != nil {
		return Transaction{}, err
	}
	a.Balance = newBalance
	tx := newTransaction(a.AccountID, TransactionTypeWithdrawal, money, referenceID, merchantName)
	a.transactions = append(a.transactions, tx)
	a.events = append(a.events, MoneyWithdrawn{ /* ... */ })
	return tx, nil
}
```

- **New creation and restoration are separated** — `New(...)` is the "real creation" that issues a new ID and accumulates an `AccountCreated` event, while `Reconstitute(...)` is the "restoration" that simply fills in values read from the DB. This separation is a precondition for upholding the root's "Aggregate boundary = transaction boundary" — because events must never be raised again at restoration time.

```go
func New(ownerID, email, currency string) *Account { /* issues a new ID + accumulates an AccountCreated event */ }
func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt, lastInterestPaidAt time.Time) *Account {
	/* restores only the state, without any events */
}
```

---

## Entity — `Transaction` (`internal/domain/account/transaction.go`)

An object **whose equality is determined by a unique identifier (`TransactionID`)**. Go has no interface that enforces `equals()`, so if needed, an explicit method is added (currently omitted since no place needs value comparison).

```go
type Transaction struct {
	TransactionID string
	AccountID     string
	Type          TransactionType
	Amount        Money
	ReferenceID   string              // another BC's Aggregate ID (paymentId/refundId) when reacting to its Integration Event; empty otherwise
	MerchantName  string              // optional payee/memo attached to a user-requested withdrawal
	Category      TransactionCategory // filled in asynchronously by CategorizeTransactionEventHandler
	CreatedAt     time.Time
}

func newTransaction(accountID string, txType TransactionType, amount Money, referenceID, merchantName string) Transaction {
	return Transaction{
		TransactionID: common.NewID(), // 32-char hex — see aggregate-id.md
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		ReferenceID:   referenceID,
		MerchantName:  merchantName,
		CreatedAt:     time.Now(),
	}
}
```

The fact that `newTransaction` is private (lowercase) matters — `Transaction` is only ever created through `Account`. It's still possible to construct one directly as a literal from outside the package (e.g. `account.Transaction{TransactionID: "x", ...}` — a Go struct literal can always be assembled as long as its fields are exported), but within the `account` package's own code there is no path that issues a `Transaction` without going through `Account`.

---

## Value Object — `Money` (`internal/domain/account/money.go`)

**An immutable object whose equality is determined by the combination of its attributes.** It has no identifier. Go's value type (a struct, not a pointer) is copied on assignment, so the language itself helps enforce "immutability" to some degree — every method always returns a new `Money` and never mutates the original, preserving immutability by convention.

```go
type Money struct {
	Amount   int64
	Currency string
}

func (m Money) Add(other Money) (Money, error) {
	if m.Currency != other.Currency {
		return Money{}, ErrCurrencyMismatch
	}
	return Money{Amount: m.Amount + other.Amount, Currency: m.Currency}, nil // returns a new value, m stays unchanged
}

func (m Money) Equals(other Money) bool {
	return m.Amount == other.Amount && m.Currency == other.Currency
}
```

The method receiver being `(m Money)` (a value receiver) is deliberate — using a pointer receiver (`(m *Money)`) would let the method mutate the original inside its body, blurring the meaning of an immutable object. `Add`/`Subtract` returning an error is also a Go idiom — since there are no exceptions, an invariant violation like "currency mismatch" is expressed via the return value.

---

## Domain Event — `events.go`

Using past-tense names (`AccountCreated`, `MoneyDeposited`, `AccountSuspended`) matches the root principle. Since Go has no union types, the relationship "one of these events" is expressed via **an interface sharing an empty marker method**:

```go
type DomainEvent interface {
	isAccountDomainEvent()
}

type AccountCreated struct{ /* ... */ }
func (AccountCreated) isAccountDomainEvent() {}
```

The consuming side (`notification/service.go`) distinguishes the actual event kind with a type switch — Go's `switch e := event.(type)` plays the same role as TypeScript's `instanceof` chaining:

```go
func describe(event account.DomainEvent) (string, emailContent, bool) {
	switch e := event.(type) {
	case account.AccountCreated:
		return "AccountCreated", emailContent{ /* uses e.AccountID etc. */ }, true
	case account.MoneyDeposited:
		return "MoneyDeposited", emailContent{ /* ... */ }, true
	// ...
	default:
		return "", emailContent{}, false
	}
}
```

---

## A Go-specific constraint — no true encapsulation

TypeScript/Java/Kotlin enforce instance-level encapsulation with the `private` keyword. **Go only encapsulates at the package level** — a lowercase identifier (`events`, `transactions`, `newTransaction`) is only "inaccessible from other packages"; **it remains fully accessible from any other file/type within the same `account` package.** In other words:

- The `Account.events` field can never be read or written directly from outside the `account` package (e.g. `internal/application/command`) — it can only be accessed through the `DomainEvents()`/`ClearEvents()` methods. This boundary is reliably enforced.
- But nothing stops the compiler from letting some other code accidentally added within the same `account` package (e.g. a newly added helper function) execute `a.events = nil` directly. The rule "package == encapsulation boundary" has to be upheld as team discipline.
- This is **why it's advantageous to split one package per Aggregate** — gathering all types related to `Account`, `Transaction`, `Money`, `DomainEvent` inside the `internal/domain/account/` package makes that package boundary itself coincide with the boundary of "code responsible for this Aggregate's invariants." Placing multiple Aggregates in the same package blurs this boundary.

Be clear that when adding a new domain, this constraint needs to be supplemented with a team convention (in code review: "never touch an Aggregate field directly, even from inside the package, without going through a method").

---

## Criteria for deciding Aggregate boundaries

The same criteria as the root document apply — objects that are created/deleted together and share invariants are grouped into the same Aggregate. `Account` and `Transaction` are an example: `Transaction` is only ever created through `Account.Deposit()`/`Withdraw()`, and cannot exist separately from `Account`'s `Balance` invariant. Conversely, different Aggregates (e.g. `Account` and `Payment`, or `Payment` and `Refund` within the same Payment BC) are connected only via ID references (`PaymentID string`, etc.), never object references — `internal/domain/payment/payment.go` and `refund.go` are the real example of this rule (`RefundEligibilityService` from the root [domain-service.md](../../../../docs/architecture/domain-service.md)).

This boundary is automatically checked by `implementations/go/harness/no_cross_aggregate_reference.go` (the `no-cross-aggregate-reference` rule) — it flags FAIL if `Payment` has a `Refund` type, or `Refund` has a `Payment` type, directly as a struct field (an ID string field passes).

The rule that generalizes this same principle across the entire Bounded Context boundary is `implementations/go/harness/no_cross_bc_domain_import.go` (the `no-cross-bc-domain-import` rule) — it flags FAIL if `internal/domain/<bc>/*.go` imports another BC's `internal/domain/<other-bc>` package (e.g. `card` directly importing `payment`). Unlike `no-cross-aggregate-reference`, which does a precise check only for Payment↔Refund within the same BC (payment), this rule blocks any domain-to-domain package import across every pair of BCs.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — the Domain layer's position and dependency direction
- [aggregate-id.md](aggregate-id.md) — ID issuance rules (`common.NewID()`, 32-character hex)
- [domain-events.md](domain-events.md) — Outbox processing after event collection
- [repository-pattern.md](repository-pattern.md) — Repository per Aggregate
- [error-handling.md](error-handling.md) — the pattern of returning errors from domain methods
