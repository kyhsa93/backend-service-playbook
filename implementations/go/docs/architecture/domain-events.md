# Domain Events (Go) — Collection, Outbox, Poller/Consumer

This implements as-is the single path the root [domain-events.md](../../../../docs/architecture/domain-events.md) prescribes — **the Outbox record happens inside Repository.Save's transaction, the queue publish is done by the independently-ticking `outbox.Poller`, and EventHandler execution is done by `outbox.Consumer`, which waits on SQS**. This repository has no path where the Command Handler synchronously drains within the same process right after saving. `internal/infrastructure/outbox/poller.go`/`consumer.go` are the actual code, and this document explains, grounded in that actual code: (1) the Aggregate's event collection, (2) the Outbox record (inside the Repository save transaction), and (3) asynchronous publish/consume via the Poller/Consumer.

---

## Step 1: collecting events on the Aggregate — correctly implemented

`internal/domain/account/events.go` defines the `DomainEvent` interface and the event types, and the domain methods in `account.go` accumulate events into a slice on a state change:

```go
// internal/domain/account/events.go
type DomainEvent interface {
	isAccountDomainEvent()
}

type MoneyDeposited struct {
	AccountID     string
	Email         string
	TransactionID string
	Amount        Money
	BalanceAfter  Money
	CreatedAt     time.Time
}

func (MoneyDeposited) isAccountDomainEvent() {}
```

```go
// internal/domain/account/account.go
func (a *Account) Deposit(amount int64) (Transaction, error) {
	// ... validate invariants, change state ...
	a.events = append(a.events, MoneyDeposited{
		AccountID: a.AccountID, Email: a.Email, TransactionID: tx.TransactionID,
		Amount: money, BalanceAfter: a.Balance, CreatedAt: tx.CreatedAt,
	})
	return tx, nil
}

func (a *Account) DomainEvents() []DomainEvent { return a.events }
func (a *Account) ClearEvents()                { a.events = nil }
```

`isAccountDomainEvent()` is an idiom used because Go has no sealed interface (union type) — since only types implementing this empty method satisfy `DomainEvent`, an arbitrary type outside the package is prevented from accidentally satisfying `DomainEvent` (mimicking what TypeScript's discriminated union does, via a Go unexported method).

This step 1 matches the root principle exactly. Steps 2, 3, and 4 also actually have the same transactional atomicity and independently-ticking publish/consume, as shown below.

---

## Step 2: the Outbox record — inside the Repository save transaction

As the root document requires, the Aggregate state and the domain events are committed together **inside** the Repository's save method, **in the same transaction**.

```sql
-- migrations/0003_add_outbox.sql
CREATE TABLE outbox (
  event_id    VARCHAR(36)  PRIMARY KEY,
  event_type  VARCHAR(50)  NOT NULL,
  payload     JSONB        NOT NULL,
  processed   BOOLEAN      NOT NULL DEFAULT false,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_unprocessed ON outbox (created_at) WHERE processed = false;
```

`Writer.SaveAll` in `internal/infrastructure/outbox/writer.go` serializes an event to JSON and inserts it into outbox — the event type name is obtained via reflection (`reflect.TypeOf(event).Name()`) and written as-is into the `event_type` column (e.g. `account.MoneyDeposited{}` → `"MoneyDeposited"`):

```go
// internal/infrastructure/outbox/writer.go
func (w *Writer) SaveAll(ctx context.Context, tx *sql.Tx, events []account.DomainEvent) error {
	for _, event := range events {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			common.NewID(), eventTypeName(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}
```

`SaveAccount` in `internal/infrastructure/persistence/account_repository.go` calls `outboxWriter.SaveAll` and then commits, inside **the same `*sql.Tx`** it used to save the account/transaction rows — since the commit is atomic, "the account changed but the event wasn't recorded" simply cannot happen:

```go
// internal/infrastructure/persistence/account_repository.go
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
	tx, err := r.db.BeginTx(ctx, nil)
	// ...
	defer tx.Rollback()

	// ... accounts row upsert, transactions row insert ...

	if err := r.outboxWriter.SaveAll(ctx, tx, a.DomainEvents()); err != nil {
		return err
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}
```

The Command Handler simply returns once `repo.SaveAccount(ctx, a)` finishes — it knows nothing about outbox or SQS at all:

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	// ...
	tx, err := a.Deposit(cmd.Amount, "")
	// ...
	if err := h.repo.SaveAccount(ctx, a); err != nil { // ← account state + outbox row, committed in the same transaction
		return nil, err
	}
	return &tx, nil // ends here — the Poller/Consumer process it independently.
}
```

**This repository never uses the approach of "draining synchronously right after saving, within the same process."** `implementations/go/harness/outbox_drain_order.go` flags FAIL if a Command Handler (`*_handler.go`, excluding `*_event_handler.go`) references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls something like `ProcessPending()`/`Poll()`/`drainOnce()` (the `outbox-drain-order` rule).

---

## Step 3: the Poller — Outbox → SQS transmission

`Poller.Run(ctx)` in `internal/infrastructure/outbox/poller.go` runs on its own with a `time.NewTicker(1 * time.Second)`, ticking every second — the Command Handler never references this struct at all. It's a background goroutine `cmd/server/main.go` starts with `go outboxPoller.Run(ctx)`, sharing the same `ctx` that `signal.NotifyContext` cancels so it stops together during graceful shutdown.

```go
// internal/infrastructure/outbox/poller.go
func (p *Poller) Run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := p.drainOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "outbox polling failed", "error", err)
			}
		}
	}
}

func (p *Poller) drainOnce(ctx context.Context) error {
	rows, err := p.fetchPending(ctx) // SELECT ... WHERE processed = false ORDER BY created_at LIMIT 100
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "SQS publish failed", "event_type", row.eventType, "event_id", row.eventID, "error", err)
		}
	}
	return nil
}

func (p *Poller) publish(ctx context.Context, row outboxRow) error {
	if _, err := p.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(row.payload)),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"eventType": {DataType: aws.String("String"), StringValue: aws.String(row.eventType)},
		},
	}); err != nil {
		return fmt.Errorf("send sqs message: %w", err)
	}
	_, err := p.db.ExecContext(ctx, `UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID)
	return err
}
```

`processed=true` now means "delivery to SQS finished," not "the handler finished processing" — from this point on, retry/at-least-once guarantees are the responsibility of SQS's visibility timeout + DLQ, not the outbox table. A row that fails to publish stays `processed=false` and is retried on the next tick.

---

## Step 4: the Consumer — SQS → Handler execution

`Consumer.Run(ctx)` in `internal/infrastructure/outbox/consumer.go` long-polls via `ReceiveMessage`'s `WaitTimeSeconds: 5`, and when it receives a message, looks up the handler in the `handlers` map by `eventType` (MessageAttributes) and calls it. It's a background goroutine `main.go` starts exactly once with `go outboxConsumer.Run(ctx)` — it is never created freshly per request.

```go
// internal/infrastructure/outbox/consumer.go
func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		result, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:              aws.String(c.queueURL),
			MaxNumberOfMessages:   10,
			MessageAttributeNames: []string{"eventType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil {
				return // ReceiveMessage was canceled during graceful shutdown
			}
			slog.ErrorContext(ctx, "SQS receive failed", "error", err)
			continue
		}
		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	eventType := ""
	if attr, ok := message.MessageAttributes["eventType"]; ok && attr.StringValue != nil {
		eventType = *attr.StringValue
	}
	handler, ok := c.handlers[eventType]
	if !ok {
		slog.ErrorContext(ctx, "no registered handler found — leaving for retry", "event_type", eventType)
		return // not deleted — it will be redelivered and retried after the visibility timeout.
	}
	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "event processing failed", "event_type", eventType, "error", err)
		return // not deleted — it will be redelivered and retried after the visibility timeout.
	}
	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl: aws.String(c.queueURL), ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "failed to delete message", "event_type", eventType, "error", err)
	}
}
```

`handlers` (`map[string]outbox.Handler`, `Handler = func(ctx, payload []byte) error`) is a **single shared map** that `cmd/server/main.go` assembles exactly once — all 6 domain event handlers from `internal/application/event/` and the Integration Event handlers (glue closures) that the Card/Payment BC react to are all registered into this one map:

```go
// internal/application/event/account_created_event_handler.go — satisfies the outbox.Handler signature
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
```

```go
// cmd/server/main.go (excerpt)
outboxHandlers := map[string]outbox.Handler{
	"AccountCreated": event.NewAccountCreatedEventHandler(notifier).Handle,
	// ... the remaining Domain Event Handlers ...
	"account.suspended.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountSuspendedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return suspendCardsHandler.Handle(ctx, command.SuspendCardsByAccountCommand{AccountID: e.AccountID})
	},
	// ... the remaining Integration Event reactions ...
}
outboxPoller := outbox.NewPoller(db, sqsClient, sqsConfig.QueueURL)
outboxConsumer := outbox.NewConsumer(sqsClient, sqsConfig.QueueURL, outboxHandlers)

go outboxPoller.Run(ctx)
go outboxConsumer.Run(ctx)
```

`notification.Service.Notify` returns an error on failure, and the Consumer doesn't delete that message, letting SQS redeliver it:

```go
// internal/infrastructure/notification/service.go
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) error {
	eventType, content, ok := describe(event)
	if !ok {
		return nil
	}
	if err := s.send(ctx, eventType, content); err != nil {
		return fmt.Errorf("notify %s: %w", eventType, err)
	}
	return nil
}
```

This path eliminates the failure mode of "the DB committed, but the notification send failed and was lost" — the moment an Outbox row is committed, the event is guaranteed to "eventually reach the queue and be processed" (at-least-once, with the outbox's retries handed off to SQS's visibility timeout + DLQ), and if the account save itself is rolled back, the event never existed in the first place.

### The SQS client — actual code

`NewSQSClient()` in `internal/infrastructure/outbox/sqs_client.go` follows exactly the same setup as `notification.NewSESClient()` (the `AWS_ENDPOINT_URL` branch, static test credential defaults) — the Poller/Consumer share this one client. The queue URL is read by `LoadSQSConfig()` in `internal/config/sqs.go` from the `SQS_DOMAIN_EVENT_QUEUE_URL` environment variable (fail-fast, the same pattern as `DatabaseConfig`).

---

## Integration Events / idempotency

Because there are two Bounded Contexts, Account and Card, an actual Integration Event exists (a public cross-BC contract, versioned as `account.suspended.v1`/`account.closed.v1`) — see `account_suspended_integration_event.go`/`account_closed_integration_event.go` under `internal/application/integration-event/`. Card's reaction use cases (`suspend_cards_by_account_handler.go`/`cancel_cards_by_account_handler.go`) are implemented idempotently by only selecting and processing cards that are ACTIVE (or ACTIVE·SUSPENDED) — assuming SQS's at-least-once delivery, they're harmless even if the same event is received again. See [cross-domain.md](cross-domain.md) for details. Of the 3-tier idempotency strategy for event handlers (intrinsic idempotency / Ledger / strong atomicity), Card BC's reaction actually uses intrinsic idempotency (state-transition based), while Payment BC's reaction (`WithdrawByPaymentHandler`/`DepositByPaymentHandler`) actually uses a `referenceId`-based Level 2 Ledger (`HasTransactionWithReference`).

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Domain Event definitions, the past-tense naming rule
- [repository-pattern.md](repository-pattern.md) — the Repository's save responsibility and the point at which the Outbox is saved
- [persistence.md](persistence.md) — transaction propagation (the prerequisite for including the Outbox in the same transaction)
- [scheduling.md](scheduling.md) — the Task Queue vs Domain Event distinction, DLQ/idempotency conventions
- [local-dev.md](local-dev.md) — running SQS locally via LocalStack
