# Cross-Domain Call Pattern (Go)

> The **selection criteria** for synchronous (Adapter) vs asynchronous (Integration Event) follow the root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) as-is. This document covers how to implement both patterns in Go.

This repository's `examples/` has **two Bounded Contexts** — Account and Card. When a card is issued, Card checks whether the linked account is active via a **synchronous Adapter (ACL)**, and when the account is suspended/closed, Card follows suit and changes its own card status via an **asynchronous Integration Event**. Every example below is actual code.

```
[Card BC]                                                  [Account BC]
  internal/application/command/
    account_adapter.go (AccountAdapter interface + AccountView)
    issue_card_handler.go (uses AccountAdapter — synchronous)
    suspend_cards_by_account_handler.go (asynchronous reaction use case)
  internal/infrastructure/acl/
    account_adapter.go (implementation) ──────import──────▶  internal/domain/account (account.Query)
  internal/domain/card/ ...

  ▲ asynchronous: account.suspended.v1 / account.closed.v1 (via Outbox)
```

---

## Pattern 1 — synchronous Adapter/ACL (querying the account at card issuance)

Card issuance is a synchronous call, since "does the linked account exist and is it active?" must be judged **immediately, at response time**. Card never references Account's Repository/domain model directly — it accesses it only through a minimal interface (Adapter) defined in its own package.

### Step 1 — define the interface in the calling side's (Card's) Application layer

The interface and the return type live **in the calling side's (Card's) package**. Instead of exposing Account BC's `Status` enum or domain model, it's defined only in the minimal shape Card actually uses (`active bool`) — the purpose of ACL is to prevent an upstream (Account) model change from leaking into Card.

```go
// internal/application/command/account_adapter.go
package command

import "context"

// AccountView is a value holding only the minimal information the Card BC actually needs about an account.
type AccountView struct {
	AccountID string
	Active    bool
}

// AccountAdapter is the port (ACL interface) through which Card BC synchronously
// looks up an account. Returns (nil, nil) if the account isn't found — it's the
// implementation's responsibility to translate the upstream's "account not
// found" error type into a nil signal instead of letting it leak into Card.
type AccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*AccountView, error)
}
```

Thanks to Go's structural typing, Account BC doesn't even need to know this interface exists.

### Step 2 — write the implementation in the calling side's (Card's) Infrastructure layer

The implementation (ACL) lives in Card's `internal/infrastructure/acl/`. This implementation imports the Account domain and calls `account.Query` — the dependency direction is "Card Infrastructure → Account domain," and Card's Application/Domain layers know nothing about Account at all.

```go
// internal/infrastructure/acl/account_adapter.go
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

type AccountAdapter struct {
	accounts account.Query // the read interface Account BC exposes
}

func NewAccountAdapter(accounts account.Query) *AccountAdapter {
	return &AccountAdapter{accounts: accounts}
}

var _ command.AccountAdapter = (*AccountAdapter)(nil) // compile-time verification

func (a *AccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	acc, err := account.FindOne(ctx, a.accounts, accountID, ownerID)
	if err != nil {
		// translates the upstream's "account not found" into a nil signal Card understands (preventing contamination).
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("account adapter find account: %w", err)
	}
	// translates Account's Status enum into just an active bool, without exposing it.
	return &command.AccountView{AccountID: acc.AccountID, Active: acc.Status == account.StatusActive}, nil
}
```

> **When calling an Account deployed as a separate service**, `AccountAdapter` wraps an HTTP/gRPC client instead of being injected with `account.Query` directly (an `http.Client` + `context.Context` for timeout propagation). The interface definition (Step 1) stays exactly the same, and only the implementation's internals change to a network call — this is the key benefit of the Adapter pattern.

### Step 3 — using the Adapter in a Command Handler

```go
// internal/application/command/issue_card_handler.go
func (h *IssueCardHandler) Handle(ctx context.Context, cmd IssueCardCommand) (*card.Card, error) {
	view, err := h.accounts.FindAccount(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if view == nil {
		return nil, card.ErrLinkedAccountNotFound
	}
	if !view.Active {
		return nil, card.ErrIssueRequiresActiveAccount
	}
	c := card.IssueCard(cmd.AccountID, cmd.RequesterID, cmd.Brand)
	if err := h.repo.SaveCard(ctx, c); err != nil {
		return nil, err
	}
	return c, nil
}
```

---

## Pattern 2 — asynchronous Integration Event (an account state change propagates to Card)

When an account is suspended/closed, its cards must be suspended/closed to match. Since this is "changing another BC's state," it's done not via a synchronous Adapter but via an **asynchronous Integration Event** (the root principle). It's loosely coupled through the Outbox, in the direction where Account never imports Card.

### Step A — Account records an Integration Event into the Outbox

Account's Aggregate only raises an internal Domain Event (`AccountSuspended`). The Application EventHandler (`internal/application/event/`) that processes that Domain Event converts it into a **versioned Integration Event** for external BCs (`account.suspended.v1`) and records it as a new row in the Outbox — the conversion point is always the EventHandler, and the Aggregate never creates an Integration Event directly.

```go
// internal/application/integration-event/account_suspended_integration_event.go
package integrationevent

type AccountSuspendedV1 struct {
	AccountID   string    `json:"accountId"`
	SuspendedAt time.Time `json:"suspendedAt"`
}

func (AccountSuspendedV1) EventName() string { return "account.suspended.v1" }
```

```go
// internal/application/event/account_suspended_event_handler.go (excerpt)
func (h *AccountSuspendedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountSuspended
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountSuspended: %w", err)
	}
	// records the Integration Event for external BCs into the Outbox (a separate row).
	ie := integrationevent.AccountSuspendedV1{AccountID: evt.AccountID, SuspendedAt: evt.SuspendedAt}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish account.suspended.v1: %w", err)
	}
	// the notification is best-effort — it never returns an error on failure
	// (returning one would trigger redraining and duplicate-publish the
	// Integration Event above). A send failure is only logged.
	if err := h.notifier.Notify(ctx, evt); err != nil {
		slog.ErrorContext(ctx, "account suspended notification failed", "account_id", evt.AccountID, "error", err)
	}
	return nil
}
```

`publisher` is `outbox.Publisher`, which inserts one Outbox row over its own connection. Since this handler itself already runs asynchronously — invoked when `outbox.Consumer` receives an `AccountSuspended` message from SQS — the `account.suspended.v1` row newly recorded here isn't drained within that same pass. The next tick of `outbox.Poller` naturally picks it up and publishes it to SQS, and `outbox.Consumer` receives it again and runs Card's reaction use case — there's no multi-pass loop that repeatedly processes a newly-recorded row within the same pass.

### Step B — Card subscribes to the Integration Event (a reaction use case)

Card's reaction use case is a pure Card use case (it never imports Account). It's implemented to be **idempotent** — since it only suspends ACTIVE cards, it's harmless even if the same event is received again (at-least-once).

```go
// internal/application/command/suspend_cards_by_account_handler.go (excerpt)
func (h *SuspendCardsByAccountHandler) Handle(ctx context.Context, cmd SuspendCardsByAccountCommand) error {
	cards, _, err := h.repo.FindCards(ctx, card.FindQuery{
		AccountID: cmd.AccountID,
		Status:    []card.Status{card.StatusActive}, // ACTIVE only → idempotent
		Take:      1000,
	})
	if err != nil {
		return fmt.Errorf("suspend cards by account: %w", err)
	}
	for _, c := range cards {
		if err := c.Suspend(); err != nil {
			return err
		}
		if err := h.repo.SaveCard(ctx, c); err != nil {
			return err
		}
	}
	return nil
}
```

### Step C — `main.go` (the composition root) connects the two BCs by event_type string

Neither the Account package nor the Card package imports the other. **Only the composition root (`main.go`)** knows about both sides, and it connects the `account.suspended.v1` string to Card's reaction use case in the Outbox's flat `map[string]outbox.Handler` — only unmarshal glue lives here, and the real logic is delegated to a Card Application handler. This map is just the handler table `outbox.Consumer` references when running a message it received from SQS (asynchronous publishing is handled by a separate `outbox.Poller` — see domain-events.md).

```go
// cmd/server/main.go (excerpt)
outboxHandlers := map[string]outbox.Handler{
	// ... Account's internal Domain Event handlers ...
	"AccountSuspended": event.NewAccountSuspendedEventHandler(notifier, outboxPublisher).Handle,
	"AccountClosed":    event.NewAccountClosedEventHandler(notifier, outboxPublisher).Handle,
	// Card BC reacts to Account's Integration Event (asynchronously).
	"account.suspended.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountSuspendedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return suspendCardsHandler.Handle(ctx, command.SuspendCardsByAccountCommand{AccountID: e.AccountID})
	},
	"account.closed.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountClosedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return cancelCardsHandler.Handle(ctx, command.CancelCardsByAccountCommand{AccountID: e.AccountID})
	},
}
outboxConsumer := outbox.NewConsumer(sqsClient, queueURL, outboxHandlers)
```

NestJS resolves this subscription via `EventHandlerRegistry` + DI, but in Go, all it takes is adding an entry to the map in `main.go` — Account↔Card non-dependency is preserved with no separate registry needed.

---

## Principles

- **Never inject another domain's Repository/Service directly into the Application/Domain layer** — always go through an Adapter interface defined in your own package (Pattern 1).
- **Define the Adapter interface and return type in the minimal shape the calling side needs** — never expose the callee's enum/model/errors (ACL).
- **Never use an Adapter for writes (state changes)** — if another BC's state needs to change, switch to an Integration Event (asynchronous) (Pattern 2, the Outbox pattern from [domain-events.md](domain-events.md)).
- **Maintain non-dependency between BCs via an event_type string + composition-root wiring** — neither Account nor Card imports the other; only `main.go` knows both.
- **Make asynchronous reaction use cases idempotent** — assuming at-least-once delivery, an already-processed state must never be touched again.
- **Propagate `context.Context` as-is** — an Adapter call must respect cancellation/deadlines the same way a Repository call does.

The first principle (never inject another domain's Repository directly into the Application layer) is automatically checked by `implementations/go/harness/cross_bc_application_import.go` (the `no-cross-bc-repository-in-application` rule) — it flags FAIL if a single file under `internal/application/` imports `internal/domain/<bc>/` packages from two different Bounded Contexts at once (importing only one BC's domain package is fine).

---

### Related documents

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — synchronous/asynchronous selection criteria (root, language-agnostic)
- [repository-pattern.md](repository-pattern.md) — separating the interface from the implementation, the compile-time verification idiom
- [domain-events.md](domain-events.md) — the Outbox pattern (Writer/Poller/Consumer/Publisher) and idempotency
- [module-pattern.md](module-pattern.md) — the `main.go` assembly order where the Adapter/handler wiring happens
