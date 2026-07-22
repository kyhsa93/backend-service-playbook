# Scheduling / Batch Jobs (Go)

The principle follows the root [scheduling.md](../../../../docs/architecture/scheduling.md): the Scheduler only **enqueues** in the Infrastructure layer, and actual execution is delegated through the path Task Consumer → Task Controller (Interface layer) → Command Service. Task enqueueing goes through the `task_outbox` table to guarantee atomicity, and the Command Handler is implemented to be idempotent.

`examples/` has two batch jobs that actually use this pattern — **daily interest payment** (pays interest daily to every ACTIVE account) and **monthly card usage statement dispatch** (emails last month's payment summary to every ACTIVE card). Below explains that actual implementation.

---

## Overall flow

```
[InterestScheduler/StatementScheduler]   internal/infrastructure/scheduling/
  --(enqueue)-->
[task_outbox table]                     same process, a single-row INSERT with no transaction
  --(taskqueue.Poller, 1s tick)-->
[task-queue SQS queue]                      internal/infrastructure/task-queue/
  --(taskqueue.Consumer, long polling)-->
[InterestTaskController/StatementTaskController]  internal/interface/task/
  --(calls)-->
[ApplyDailyInterestHandler/SendCardUsageStatementHandler]  internal/application/command/
```

This uses a separate table (`task_outbox`) and a separate SQS queue (`task-queue`), completely distinct from `domain-events` (Outbox → SQS `domain-events` queue) — because "a fact happened" (Domain Event) and "command: do X" (Task) are different units of meaning (see the "Task Queue vs Domain Event" distinction in [domain-events.md](domain-events.md)). `internal/infrastructure/outbox/` and `internal/infrastructure/task-queue/` are structural mirror images of each other (Writer/Poller/Consumer), but they're two entirely independent pairs.

---

## Scheduler — `time.Ticker`-based, Infrastructure layer

The Go standard library has no Cron expression parser. Both Schedulers just use a 24-hour tick via `time.Ticker`.

```go
// internal/infrastructure/scheduling/interest_scheduler.go
type InterestScheduler struct {
	taskQueue TaskQueue
}

func (s *InterestScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.EnqueueDailyInterest(ctx, time.Now().UTC()); err != nil {
				slog.ErrorContext(ctx, "failed to enqueue interest payment task", "error", err)
			}
		}
	}
}

// EnqueueDailyInterest is exported so tests/operational tools can call it
// directly without waiting for a real tick.
func (s *InterestScheduler) EnqueueDailyInterest(ctx context.Context, today time.Time) error {
	date := today.Format("2006-01-02")
	dedupID := "account.apply-interest-" + date
	payload := []byte(`{"date":"` + date + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.apply-interest", payload, dedupID)
}
```

Since `StatementScheduler` (`internal/infrastructure/scheduling/statement_scheduler.go`) can't express a "month" period with a fixed-length `time.Ticker`, it approximates this with a gate on every 24-hour tick — "is today the 1st?" The responsibility for the exact execution time isn't here, but on the `task_outbox.dedup_id` UNIQUE constraint and the idempotency of `Card.MarkStatementSent`.

Whether references to `time.Ticker`/`time.NewTicker` only appear in `internal/infrastructure/` is automatically checked by `implementations/go/harness/scheduler_in_infrastructure_only.go`.

Assembled via constructors in `main.go` just like other infrastructure, sharing graceful-shutdown's signal context so it stops together when the app exits:

```go
// cmd/server/main.go
go interestScheduler.Run(ctx)
go statementScheduler.Run(ctx)
```

---

## Task Outbox — the `task_outbox` table, atomicity between the DB change and the enqueue

`task_outbox` (`migrations/0007_add_scheduling.sql`) is structurally similar to `outbox` in schema (`task_id`/`task_type`/`payload`/`processed`/`created_at`), but it's a completely separate table. Putting a UNIQUE constraint on the `dedup_id` column lets **the DB itself guarantee that a Cron enqueue for the same date/period can never be recorded twice**:

```go
// internal/infrastructure/task-queue/writer.go
func (w *Writer) Enqueue(ctx context.Context, taskType string, payload []byte, dedupID string) error {
	_, err := w.db.ExecContext(ctx,
		`INSERT INTO task_outbox (task_id, task_type, payload, dedup_id) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (dedup_id) DO NOTHING`,
		common.NewID(), taskType, payload, dedupID)
	...
}
```

Since the Scheduler (Cron) has no transaction context, this single-row INSERT is the entirety of the atomicity guarantee (the same reasoning the root scheduling.md explains). Even if multiple instances execute the same date's tick simultaneously, `ON CONFLICT DO NOTHING` leaves only one row.

---

## Task Consumer — a background loop in the Infrastructure layer

`internal/infrastructure/task-queue/poller.go` (`task_outbox` → SQS publish) and `consumer.go` (SQS → Task Controller invocation) are shaped exactly like the Poller/Consumer in `internal/infrastructure/outbox/` — only the message attribute name differs, using `taskType` instead of `eventType`. They're started as independent goroutines from `main.go` (always running on their own tick, unrelated to HTTP requests).

---

## Task Controller — Interface layer

`internal/interface/task/interest_task_controller.go` and `statement_task_controller.go` are input adapters symmetric to the HTTP Handler — with no logic of their own, they deserialize the payload and call the Command Service, letting errors propagate as-is (unlike an HTTP Handler, they don't catch and convert them into status codes):

```go
// internal/interface/task/interest_task_controller.go
func (c *InterestTaskController) HandleApplyInterest(ctx context.Context, payload []byte) error {
	var p interestTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.apply-interest task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.ApplyDailyInterestCommand{Date: p.Date}) // the error propagates upward as-is
}
```

Since `taskqueue.Consumer` receives this error and leaves the message undeleted, it's automatically retried after SQS's visibility timeout, and is quarantined into the DLQ once `maxReceiveCount` is exceeded.

`main.go` assembles a single shared `map[string]taskqueue.Handler` that maps the taskType string to a Task Controller method (the same idiom as the `outbox.Handler` map):

```go
taskHandlers := map[string]taskqueue.Handler{
	"account.apply-interest":    interestTaskController.HandleApplyInterest,
	"card.send-usage-statement": statementTaskController.HandleSendStatement,
}
```

---

## Idempotency — both batch jobs are Level 1 (intrinsic idempotency)

Applies the 3-tier model from domain-events.md as-is. Both batch jobs judge "has this cycle already been processed" purely from the Aggregate's own state field, with no separate Ledger table.

**Daily interest payment — `Account.LastInterestPaidAt`:**

```go
// internal/domain/account/account.go
func (a *Account) ApplyInterest(rate float64, today time.Time) (Transaction, bool, error) {
	if a.Status != StatusActive {
		return Transaction{}, false, ErrInterestRequiresActiveAccount
	}
	if isSameDate(a.LastInterestPaidAt, today) {
		return Transaction{}, false, nil // already paid today — silently skip
	}
	interestAmount := int64(float64(a.Balance.Amount) * rate) // floor(balance * rate)
	if interestAmount <= 0 {
		return Transaction{}, false, nil // if interest is 0, state isn't changed — recomputing always gives 0 too
	}
	...
	a.LastInterestPaidAt = today
	a.events = append(a.events, InterestPaid{...})
	return tx, true, nil
}
```

When interest is paid, it leaves a `Transaction` (`TransactionTypeInterest = "INTEREST"`) just like `Deposit()`, and raises an `InterestPaid` Domain Event — this event runs through the existing `outbox` (the Domain Event path) as-is, and `application/event/interest_paid_event_handler.go` consumes it and sends an email notification. **This is an example where the Task Queue (this batch job itself) and a Domain Event (the resulting fact that interest was paid) are both used together within one use case.**

**Monthly card usage statement dispatch — `Card.LastStatementSentMonth`:**

```go
// internal/domain/card/card.go
func (c *Card) MarkStatementSent(period string) bool {
	if c.LastStatementSentMonth == period {
		return false // already sent for this period — an idempotent no-op
	}
	c.LastStatementSentMonth = period
	return true
}
```

Since sending the email (an SES call) is an external side effect that `Card` knows nothing about, `SendCardUsageStatementHandler` calls `MarkStatementSent` **only after the send succeeds** — that way, if the Task is retried due to a send failure, it actually attempts to send again.

---

## Cron multi-instance safety — a date-based `dedup_id`

```go
// daily batch
dedupID := "account.apply-interest-" + today.Format("2006-01-02")

// monthly batch — period in "2006-01" format
dedupID := "card.send-usage-statement-" + period
```

Even if multiple instances execute the same day's tick simultaneously, the `task_outbox.dedup_id` UNIQUE constraint ensures only one row is recorded.

---

## Cross-BC lookup — `PaymentQueryAdapter`

The card usage summary (count and total) needs data from the Payment BC. Card's Application layer never imports the `payment` domain package directly (the no-cross-bc-repository-in-application rule) — it uses the same ACL pattern used when Account/Payment reference each other:

```go
// internal/application/command/payment_query_adapter.go — the port Card BC defines
type PaymentQueryAdapter interface {
	SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (CardPaymentSummary, error)
}

// internal/infrastructure/acl/card_payment_adapter.go — the implementation (calls payment.Query)
type CardPaymentAdapter struct{ payments payment.Query }
```

This lookup is supported by adding a `CreatedFrom`/`CreatedTo` date range filter to `payment.FindQuery` (see [repository.go](../../../../implementations/go/examples/internal/domain/payment/repository.go)). Only COMPLETED payments are aggregated — since PENDING/FAILED/CANCELLED were never actually charged.

---

## Notifications — reuses the existing `notification.Service`

The card usage statement email is sent by the same `internal/infrastructure/notification/service.go` used for account Domain Event notifications (SES sending + the `sent_emails` audit log). Since the actual sending/recording logic (`send()`) behind `Notify(event account.DomainEvent)` is a pure function that never depended on the `account` type to begin with, the new `NotifyCardStatement(...)` method reuses this same `send()` — no separate notification mechanism is built just for the Card BC.

---

### Related documents

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event, the 3-tier idempotency model, the Outbox schema
- [layer-architecture.md](layer-architecture.md) — the rationale for placing the Scheduler in Infrastructure
- [graceful-shutdown.md](graceful-shutdown.md) — shutdown handling for background goroutines
- [observability.md](observability.md) — explicit logging of Cron failures
