# Practical Implementation Template (Go)

An example implementing one full domain (`Order`) with this architecture. Copy this template as a starting point when adding a new domain. This repository's `examples/` (the Account domain) actually uses the same structure too, so consult it alongside this template.

> This template is **target-state** code that follows exactly the correct ID format (32-character hex with hyphens removed) prescribed by [aggregate-id.md](architecture/aggregate-id.md) and the sentinel error pattern from [error-handling.md](architecture/error-handling.md). The Account domain code in `examples/` itself has a few known gaps (UUID hyphens not removed, etc.), and this document doesn't reproduce those gaps — each gap and its rationale is documented in the corresponding `architecture/*.md` document. Outbox-based event publishing (see below) is a pattern `examples/` already actually implements, and this template carries that structure over into the `Order` domain.

---

## Directory structure

```
cmd/
  server/
    main.go                          ← entry point, dependency assembly

internal/
  common/
    id.go                             ← common.NewID() — 32-character hex with hyphens removed

  domain/
    order/
      order.go                        ← Aggregate Root (New/Reconstitute + domain methods)
      order_item.go                   ← Value Object
      order_status.go                 ← Status type (enum equivalent)
      events.go                       ← DomainEvent interface + event structs
      errors.go                       ← sentinel errors
      repository.go                   ← Repository interface + FindQuery

  application/
    command/
      create_order_handler.go         ← CreateOrderCommand + CreateOrderHandler
      cancel_order_handler.go         ← CancelOrderCommand + CancelOrderHandler
    query/
      get_order_handler.go            ← GetOrderQuery + GetOrderHandler
      get_orders_handler.go           ← GetOrdersQuery + GetOrdersHandler
      result.go                       ← Result structs
    event/
      order_placed_handler.go         ← the OrderPlaced handler run by the Outbox Consumer — sends a notification
      order_cancelled_handler.go

  infrastructure/
    persistence/
      order_repository.go             ← order.Repository implementation (also records Outbox rows in the Save transaction)
    notification/
      service.go                      ← notification sending (log/email, etc.) invoked by the event handlers
    outbox/
      writer.go                       ← records events as Outbox rows within the Repository.Save transaction
      sqs_client.go                   ← creates the SQS client shared by the Poller/Consumer
      poller.go                       ← periodically reads unprocessed Outbox rows and publishes them to SQS (see domain-events.md)
      consumer.go                     ← SQS receive → runs the Handler per event_type (see domain-events.md)

  interface/
    http/
      router.go                       ← net/http routing + dependency assembly support
      order_handler.go                ← HTTP handler
      dto.go                          ← request/response DTOs

migrations/
  0001_init.sql

test/
  order_e2e_test.go
```

As more domains are added, files grow per domain, like `internal/domain/<domain>/`, `internal/application/command/<domain>_*.go`, `internal/infrastructure/persistence/<domain>_repository.go`. Go uses the structure with the layer at the top level and the domain underneath it (see [directory-structure.md](architecture/directory-structure.md)) — the opposite of NestJS, which puts the domain at the top level.

---

## Domain layer

### Aggregate Root

```go
// internal/domain/order/order.go — framework-agnostic, imports only the standard library + a minimal dependency
package order

import (
	"time"

	"github.com/example/order-service/internal/common"
)

type Order struct {
	OrderID    string
	CustomerID string
	Items      []OrderItem
	Status     Status
	CreatedAt  time.Time
	UpdatedAt  time.Time
	events     []DomainEvent // starts lowercase — not directly accessible from outside the package
}

// New creates a new order. At least one order item is required.
// The ID is issued here — the fact that ID isn't in the parameter list is
// itself what enforces the rule "a client-supplied ID is never accepted" in code.
func New(customerID string, items []OrderItem) (*Order, error) {
	if len(items) == 0 {
		return nil, ErrEmptyItems
	}
	now := time.Now()
	o := &Order{
		OrderID:    common.NewID(),
		CustomerID: customerID,
		Items:      items,
		Status:     StatusPending,
		CreatedAt:  now,
		UpdatedAt:  now,
	}
	o.events = append(o.events, OrderPlaced{
		OrderID:     o.OrderID,
		CustomerID:  customerID,
		TotalAmount: o.TotalAmount(),
		CreatedAt:   now,
	})
	return o, nil
}

// Reconstitute restores an Order from values read from the DB. It never raises events.
func Reconstitute(orderID, customerID string, items []OrderItem, status Status, createdAt, updatedAt time.Time) *Order {
	return &Order{
		OrderID:    orderID,
		CustomerID: customerID,
		Items:      items,
		Status:     status,
		CreatedAt:  createdAt,
		UpdatedAt:  updatedAt,
	}
}

// Cancel cancels the order. Invariants are validated only here —
// the Application Handler only calls this method and never checks the conditions directly.
func (o *Order) Cancel(reason string) error {
	if o.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	if o.Status == StatusPaid {
		return ErrPaidOrderNotCancellable
	}
	o.Status = StatusCancelled
	o.UpdatedAt = time.Now()
	o.events = append(o.events, OrderCancelled{
		OrderID:     o.OrderID,
		Reason:      reason,
		CancelledAt: o.UpdatedAt,
	})
	return nil
}

// TotalAmount calculates the sum of the order items' amounts.
func (o *Order) TotalAmount() int64 {
	var total int64
	for _, item := range o.Items {
		total += item.Price * int64(item.Quantity)
	}
	return total
}

func (o *Order) DomainEvents() []DomainEvent { return o.events }
func (o *Order) ClearEvents()                { o.events = nil }
```

### Value Object

```go
// internal/domain/order/order_item.go — an immutable object, methods defined with a value receiver
package order

type OrderItem struct {
	ItemID   string
	Name     string
	Price    int64
	Quantity int
}

// NewOrderItem validates the invariants (price/quantity greater than 0) and then returns an OrderItem.
func NewOrderItem(itemID, name string, price int64, quantity int) (OrderItem, error) {
	if price <= 0 {
		return OrderItem{}, ErrInvalidPrice
	}
	if quantity <= 0 {
		return OrderItem{}, ErrInvalidQuantity
	}
	return OrderItem{ItemID: itemID, Name: name, Price: price, Quantity: quantity}, nil
}

// Equals compares attribute-based equality — a Value Object compared by value, not by identifier.
func (i OrderItem) Equals(other OrderItem) bool {
	return i.ItemID == other.ItemID &&
		i.Name == other.Name &&
		i.Price == other.Price &&
		i.Quantity == other.Quantity
}
```

### Status (enum equivalent)

```go
// internal/domain/order/order_status.go
package order

type Status string

const (
	StatusPending   Status = "PENDING"
	StatusPaid      Status = "PAID"
	StatusCancelled Status = "CANCELLED"
)
```

### Domain Event

```go
// internal/domain/order/events.go
package order

import "time"

// DomainEvent is the marker interface shared by the Order domain events.
// Since Go has no union type, the empty method expresses the relationship "one of these events."
type DomainEvent interface {
	isOrderDomainEvent()
}

type OrderPlaced struct {
	OrderID     string
	CustomerID  string
	TotalAmount int64
	CreatedAt   time.Time
}

func (OrderPlaced) isOrderDomainEvent() {}

type OrderCancelled struct {
	OrderID     string
	Reason      string
	CancelledAt time.Time
}

func (OrderCancelled) isOrderDomainEvent() {}
```

### sentinel errors

```go
// internal/domain/order/errors.go
package order

import "errors"

var (
	ErrNotFound                = errors.New("order not found")
	ErrEmptyItems              = errors.New("order must contain at least one item")
	ErrInvalidPrice            = errors.New("item price must be greater than zero")
	ErrInvalidQuantity         = errors.New("item quantity must be greater than zero")
	ErrAlreadyCancelled        = errors.New("order already cancelled")
	ErrPaidOrderNotCancellable = errors.New("paid order cannot be cancelled")
)
```

### Repository interface

```go
// internal/domain/order/repository.go — interface only, no implementation
package order

import "context"

type FindQuery struct {
	Page       int
	Take       int
	CustomerID string
	Status     []Status
}

type Repository interface {
	FindByID(ctx context.Context, orderID, customerID string) (*Order, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Order, int, error)
	Save(ctx context.Context, o *Order) error
}
```

### Shared ID utility

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

## Application layer

### CommandHandler

The Command Handler depends only on the Repository — Outbox publish/consume is the
responsibility of the independently-ticking `outbox.Poller`/`outbox.Consumer`, unrelated
to the Command Handler (synchronous draining is prohibited, see [domain-events.md](architecture/domain-events.md)).
When a Repository/Technical Service needs to be received as an interface, the same idiom is
used as-is: declare only the minimal signature the `command` package needs, and place it
in the using side's package — `command.PasswordHasher`/`command.AccountAdapter` in
`examples/` are the real examples.

```go
// internal/application/command/create_order_handler.go
package command

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type CreateOrderItem struct {
	ItemID   string
	Name     string
	Price    int64
	Quantity int
}

type CreateOrderCommand struct {
	CustomerID string
	Items      []CreateOrderItem
}

type CreateOrderHandler struct {
	repo order.Repository
}

func NewCreateOrderHandler(repo order.Repository) *CreateOrderHandler {
	return &CreateOrderHandler{repo: repo}
}

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) (*order.Order, error) {
	items := make([]order.OrderItem, 0, len(cmd.Items))
	for _, i := range cmd.Items {
		item, err := order.NewOrderItem(i.ItemID, i.Name, i.Price, i.Quantity) // invariant validation happens in Domain
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	o, err := order.New(cmd.CustomerID, items)
	if err != nil {
		return nil, err
	}
	// Repository.Save commits the order/order_item rows and the Outbox row in the
	// same transaction (see domain-events.md) — there's no "the order was created
	// but the event was lost" failure mode. It returns immediately after saving —
	// Outbox → SQS publish/consume is solely the responsibility of the
	// independently-ticking outbox.Poller/outbox.Consumer (synchronous draining
	// is prohibited, see domain-events.md).
	if err := h.repo.Save(ctx, o); err != nil {
		return nil, fmt.Errorf("create order: %w", err)
	}
	return o, nil
}
```

```go
// internal/application/command/cancel_order_handler.go
package command

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type CancelOrderCommand struct {
	OrderID    string
	CustomerID string
	Reason     string
}

type CancelOrderHandler struct {
	repo order.Repository
}

func NewCancelOrderHandler(repo order.Repository) *CancelOrderHandler {
	return &CancelOrderHandler{repo: repo}
}

func (h *CancelOrderHandler) Handle(ctx context.Context, cmd CancelOrderCommand) error {
	// 1. fetch from Repository
	o, err := h.repo.FindByID(ctx, cmd.OrderID, cmd.CustomerID)
	if err != nil {
		return fmt.Errorf("cancel order: %w", err)
	}

	// 2. business rules are validated inside the Aggregate — the Handler only orchestrates
	if err := o.Cancel(cmd.Reason); err != nil {
		return err
	}

	// 3. save via Repository — the order row and the Outbox row are committed in
	//    the same transaction. It returns immediately after saving — the side
	//    effect (notification) is handled independently and asynchronously by
	//    outbox.Poller/outbox.Consumer (synchronous draining is prohibited, see domain-events.md).
	if err := h.repo.Save(ctx, o); err != nil {
		return fmt.Errorf("cancel order: %w", err)
	}
	return nil
}
```

### QueryHandler and Result

```go
// internal/application/query/result.go
package query

import "time"

type OrderItemResult struct {
	ItemID   string
	Name     string
	Price    int64
	Quantity int
}

type GetOrderResult struct {
	OrderID     string
	CustomerID  string
	Items       []OrderItemResult
	Status      string
	TotalAmount int64
	CreatedAt   time.Time
}

type OrderSummaryResult struct {
	OrderID     string
	Status      string
	TotalAmount int64
}

type GetOrdersResult struct {
	Orders []OrderSummaryResult
	Count  int
}
```

```go
// internal/application/query/get_order_handler.go
package query

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type GetOrderQuery struct {
	OrderID    string
	CustomerID string
}

type GetOrderHandler struct {
	repo order.Repository
}

func NewGetOrderHandler(repo order.Repository) *GetOrderHandler {
	return &GetOrderHandler{repo: repo}
}

func (h *GetOrderHandler) Handle(ctx context.Context, q GetOrderQuery) (*GetOrderResult, error) {
	o, err := h.repo.FindByID(ctx, q.OrderID, q.CustomerID)
	if err != nil {
		return nil, fmt.Errorf("get order: %w", err)
	}

	items := make([]OrderItemResult, len(o.Items))
	for i, it := range o.Items {
		items[i] = OrderItemResult{ItemID: it.ItemID, Name: it.Name, Price: it.Price, Quantity: it.Quantity}
	}

	// maps into a response-only Result instead of returning the domain Aggregate as-is.
	return &GetOrderResult{
		OrderID:     o.OrderID,
		CustomerID:  o.CustomerID,
		Items:       items,
		Status:      string(o.Status),
		TotalAmount: o.TotalAmount(),
		CreatedAt:   o.CreatedAt,
	}, nil
}
```

```go
// internal/application/query/get_orders_handler.go
package query

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type GetOrdersQuery struct {
	CustomerID string
	Status     []string
	Page       int
	Take       int
}

type GetOrdersHandler struct {
	repo order.Repository
}

func NewGetOrdersHandler(repo order.Repository) *GetOrdersHandler {
	return &GetOrdersHandler{repo: repo}
}

func (h *GetOrdersHandler) Handle(ctx context.Context, q GetOrdersQuery) (*GetOrdersResult, error) {
	statuses := make([]order.Status, len(q.Status))
	for i, s := range q.Status {
		statuses[i] = order.Status(s)
	}

	orders, count, err := h.repo.FindAll(ctx, order.FindQuery{
		Page:       q.Page,
		Take:       q.Take,
		CustomerID: q.CustomerID,
		Status:     statuses,
	})
	if err != nil {
		return nil, fmt.Errorf("get orders: %w", err)
	}

	summaries := make([]OrderSummaryResult, len(orders))
	for i, o := range orders {
		summaries[i] = OrderSummaryResult{OrderID: o.OrderID, Status: string(o.Status), TotalAmount: o.TotalAmount()}
	}

	return &GetOrdersResult{Orders: summaries, Count: count}, nil
}
```

> Just like this repository's Account example, the Query Handler above reuses the same `order.Repository` as Command, with no separate Query interface. Once the point comes where the read model needs to be split into a separate store (a read replica, search index, etc.), introduce the `Query` interface separation pattern presented in [layer-architecture.md](architecture/layer-architecture.md).

---

## Infrastructure layer

### Repository implementation

```go
// internal/infrastructure/persistence/order_repository.go
package persistence

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/example/order-service/internal/domain/order"
	"github.com/example/order-service/internal/infrastructure/outbox"
)

type OrderRepository struct {
	db           *sql.DB
	outboxWriter *outbox.Writer
}

func NewOrderRepository(db *sql.DB, outboxWriter *outbox.Writer) *OrderRepository {
	return &OrderRepository{db: db, outboxWriter: outboxWriter}
}

// Compile-time interface-satisfaction check — if even one method signature is mismatched, the build fails right here.
var _ order.Repository = (*OrderRepository)(nil)

func (r *OrderRepository) FindByID(ctx context.Context, orderID, customerID string) (*order.Order, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT id, customer_id, status, created_at, updated_at FROM orders
		 WHERE id = $1 AND customer_id = $2 AND deleted_at IS NULL`, orderID, customerID)

	var id, custID, status string
	var createdAt, updatedAt time.Time
	if err := row.Scan(&id, &custID, &status, &createdAt, &updatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, order.ErrNotFound
		}
		return nil, fmt.Errorf("find order by id: %w", err)
	}

	items, err := r.findItems(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("find order items: %w", err)
	}

	// converts the DB row into the domain Aggregate — never returns the row as-is.
	return order.Reconstitute(id, custID, items, order.Status(status), createdAt, updatedAt), nil
}

func (r *OrderRepository) FindAll(ctx context.Context, q order.FindQuery) ([]*order.Order, int, error) {
	args := []any{}
	where := []string{"deleted_at IS NULL"}
	i := 1

	// adds a condition only when the value is present — the zero value means "no condition."
	if q.CustomerID != "" {
		where = append(where, fmt.Sprintf("customer_id = $%d", i))
		args = append(args, q.CustomerID)
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

	var count int
	countQuery := fmt.Sprintf(`SELECT COUNT(*) FROM orders WHERE %s`, whereClause)
	if err := r.db.QueryRowContext(ctx, countQuery, args...).Scan(&count); err != nil {
		return nil, 0, fmt.Errorf("count orders: %w", err)
	}

	pagedArgs := append(append([]any{}, args...), q.Take, q.Page*q.Take)
	listQuery := fmt.Sprintf(
		`SELECT id, customer_id, status, created_at, updated_at FROM orders
		 WHERE %s ORDER BY id DESC LIMIT $%d OFFSET $%d`, whereClause, i, i+1)

	rows, err := r.db.QueryContext(ctx, listQuery, pagedArgs...)
	if err != nil {
		return nil, 0, fmt.Errorf("find orders: %w", err)
	}
	defer rows.Close()

	var orders []*order.Order
	for rows.Next() {
		var id, custID, status string
		var createdAt, updatedAt time.Time
		if err := rows.Scan(&id, &custID, &status, &createdAt, &updatedAt); err != nil {
			return nil, 0, fmt.Errorf("scan order: %w", err)
		}
		items, err := r.findItems(ctx, id)
		if err != nil {
			return nil, 0, fmt.Errorf("find order items: %w", err)
		}
		orders = append(orders, order.Reconstitute(id, custID, items, order.Status(status), createdAt, updatedAt))
	}
	return orders, count, nil
}

func (r *OrderRepository) Save(ctx context.Context, o *order.Order) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx,
		`INSERT INTO orders (id, customer_id, status, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5)
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at`,
		o.OrderID, o.CustomerID, string(o.Status), o.CreatedAt, o.UpdatedAt)
	if err != nil {
		return fmt.Errorf("save order: %w", err)
	}

	for _, item := range o.Items {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO order_items (order_id, item_id, name, price, quantity)
			 VALUES ($1, $2, $3, $4, $5)
			 ON CONFLICT (order_id, item_id) DO NOTHING`,
			o.OrderID, item.ItemID, item.Name, item.Price, item.Quantity,
		); err != nil {
			return fmt.Errorf("save order item: %w", err)
		}
	}

	// the Outbox row is recorded within the same transaction as the order/order_item
	// rows — since the commit is atomic, there's no "the order changed but the
	// event was lost" (dual-write) failure mode.
	if err := r.outboxWriter.SaveAll(ctx, tx, o.DomainEvents()); err != nil {
		return fmt.Errorf("save outbox events: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save order: %w", err)
	}
	o.ClearEvents()
	return nil
}

func (r *OrderRepository) findItems(ctx context.Context, orderID string) ([]order.OrderItem, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT item_id, name, price, quantity FROM order_items WHERE order_id = $1`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []order.OrderItem
	for rows.Next() {
		var it order.OrderItem
		if err := rows.Scan(&it.ItemID, &it.Name, &it.Price, &it.Quantity); err != nil {
			return nil, err
		}
		items = append(items, it)
	}
	return items, nil
}
```

### Outbox implementation — Writer / Poller / Consumer

The Outbox record (Writer, inside the Repository transaction) and publish/consume (Poller,
Consumer, independent goroutines) are different components — see [domain-events.md](architecture/domain-events.md) for the detailed reasoning.

```go
// internal/infrastructure/outbox/writer.go
package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"

	"github.com/example/order-service/internal/common"
	"github.com/example/order-service/internal/domain/order"
)

// Writer records the Domain Events an Aggregate accumulated while running its
// domain methods into the outbox table. It must be called inside the
// Repository's save transaction (the same *sql.Tx) so the order state change
// and the event record are committed atomically.
type Writer struct{}

func NewWriter() *Writer { return &Writer{} }

// SaveAll inserts the events into the outbox table within the given
// transaction. The caller (the Repository) is responsible for
// committing/rolling back the transaction — this method never commits.
func (w *Writer) SaveAll(ctx context.Context, tx *sql.Tx, events []order.DomainEvent) error {
	for _, event := range events {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			common.NewID(), reflect.TypeOf(event).Name(), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}
```

```go
// internal/infrastructure/outbox/sqs_client.go
package outbox

import (
	"os"

	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

// NewSQSClient creates an SQS client based on environment variables. The
// Poller/Consumer share this single client. If AWS_ENDPOINT_URL is set, it's
// redirected to an endpoint like LocalStack (see local-dev.md).
func NewSQSClient() *sqs.Client {
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	if accessKeyID == "" {
		accessKeyID = "test"
	}
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if secretAccessKey == "" {
		secretAccessKey = "test"
	}

	options := sqs.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint
	}
	return sqs.New(options)
}
```

```go
// internal/infrastructure/outbox/poller.go
package outbox

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Poller reads processed=false rows from the outbox table and publishes them
// to SQS. The Command Handler never references this struct at all — the very
// fact that Run runs independently on its own tick as a goroutine of main()
// is what eliminates "synchronously draining within the same process right
// after saving." processed=true now means "delivery to SQS finished," and
// from that point on, retry/at-least-once guarantees are the responsibility
// of SQS's visibility timeout + DLQ, not the outbox table (see domain-events.md).
type Poller struct {
	db       *sql.DB
	sqs      *sqs.Client
	queueURL string
}

func NewPoller(db *sql.DB, sqsClient *sqs.Client, queueURL string) *Poller {
	return &Poller{db: db, sqs: sqsClient, queueURL: queueURL}
}

type outboxRow struct {
	eventID   string
	eventType string
	payload   []byte
}

// Run repeats drainOnce every 1 second, stopping once ctx is canceled.
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
	rows, err := p.fetchPending(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "SQS publish failed", "event_type", row.eventType, "event_id", row.eventID, "error", err)
			// a row that fails to publish is left as processed=false and retried on the next tick.
		}
	}
	return nil
}

func (p *Poller) fetchPending(ctx context.Context) ([]outboxRow, error) {
	rows, err := p.db.QueryContext(ctx,
		`SELECT event_id, event_type, payload FROM outbox WHERE processed = false ORDER BY created_at LIMIT 100`)
	if err != nil {
		return nil, fmt.Errorf("query pending outbox rows: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var pending []outboxRow
	for rows.Next() {
		var row outboxRow
		if err := rows.Scan(&row.eventID, &row.eventType, &row.payload); err != nil {
			return nil, fmt.Errorf("scan outbox row: %w", err)
		}
		pending = append(pending, row)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate outbox rows: %w", err)
	}
	return pending, nil
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
	if _, err := p.db.ExecContext(ctx, `UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID); err != nil {
		return fmt.Errorf("mark outbox row processed: %w", err)
	}
	return nil
}
```

```go
// internal/infrastructure/outbox/consumer.go
package outbox

import (
	"context"
	"errors"
	"log/slog"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// Handler is a function that processes one event_type. The payload is passed
// through exactly as the raw JSON Writer.SaveAll saved it (deserialization is
// the Handler implementation's responsibility).
type Handler func(ctx context.Context, payload []byte) error

// Consumer waits on SQS via long polling, and once it receives a message,
// looks up the handler in the handlers map by eventType (MessageAttributes)
// and calls it. Handler succeeds → delete the message (ack). Handler fails
// (or no registered handler) → it's left undeleted — once SQS's visibility
// timeout passes, it's automatically redelivered (at-least-once, assuming
// EventHandler idempotency).
type Consumer struct {
	sqs      *sqs.Client
	queueURL string
	handlers map[string]Handler
}

func NewConsumer(sqsClient *sqs.Client, queueURL string, handlers map[string]Handler) *Consumer {
	return &Consumer{sqs: sqsClient, queueURL: queueURL, handlers: handlers}
}

// Run is a background loop started exactly once as a goroutine of main().
// Once ctx is canceled, it waits for the in-flight ReceiveMessage to finish before exiting.
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
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return
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
		return
	}
	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "event processing failed", "event_type", eventType, "error", err)
		return
	}
	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "failed to delete message", "event_type", eventType, "error", err)
	}
}
```

### Domain Event Handler — converting an event the Outbox Consumer runs into a notification

```go
// internal/application/event/order_placed_handler.go
package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

// Notifier is the minimal port this handler needs to send a notification —
// defined on the using side (the event package), so the actual
// implementation (notification.Service) doesn't even need to know this
// interface exists.
type Notifier interface {
	Notify(ctx context.Context, event order.DomainEvent) error
}

type OrderPlacedHandler struct {
	notifier Notifier
}

func NewOrderPlacedHandler(notifier Notifier) *OrderPlacedHandler {
	return &OrderPlacedHandler{notifier: notifier}
}

// Handle satisfies the outbox.Handler signature — the Consumer calls it for
// every message it receives from SQS with event_type="OrderPlaced".
func (h *OrderPlacedHandler) Handle(ctx context.Context, payload []byte) error {
	var evt order.OrderPlaced
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal OrderPlaced: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
```

`OrderCancelledHandler` in `order_cancelled_handler.go` has the same shape — it just deserializes `order.OrderCancelled` and passes it to the same `Notifier`.

```go
// internal/infrastructure/notification/service.go
package notification

import (
	"context"
	"log/slog"

	"github.com/example/order-service/internal/domain/order"
)

type Service struct{}

func NewService() *Service { return &Service{} }

// Notify handles the notification corresponding to a domain event. This
// template is a minimal implementation that only does structured logging —
// if actual email/SMS sending is needed, consult this repository's SES
// client idiom (notification/ses_client.go).
func (s *Service) Notify(ctx context.Context, event order.DomainEvent) error {
	switch e := event.(type) {
	case order.OrderPlaced:
		slog.InfoContext(ctx, "order placed", "order_id", e.OrderID, "total_amount", e.TotalAmount)
	case order.OrderCancelled:
		slog.InfoContext(ctx, "order cancelled", "order_id", e.OrderID, "reason", e.Reason)
	}
	return nil
}
```

---

## Interface layer

### DTO

```go
// internal/interface/http/dto.go
package http

import "time"

type CreateOrderItemRequest struct {
	ItemID   string `json:"itemId"`
	Name     string `json:"name"`
	Price    int64  `json:"price"`
	Quantity int    `json:"quantity"`
}

type CreateOrderRequest struct {
	Items []CreateOrderItemRequest `json:"items"`
}

type CancelOrderRequest struct {
	Reason string `json:"reason"`
}

type OrderItemResponse struct {
	ItemID   string `json:"itemId"`
	Name     string `json:"name"`
	Price    int64  `json:"price"`
	Quantity int    `json:"quantity"`
}

type GetOrderResponse struct {
	OrderID     string              `json:"orderId"`
	CustomerID  string              `json:"customerId"`
	Items       []OrderItemResponse `json:"items"`
	Status      string              `json:"status"`
	TotalAmount int64               `json:"totalAmount"`
	CreatedAt   time.Time           `json:"createdAt"`
}

type OrderSummaryResponse struct {
	OrderID     string `json:"orderId"`
	Status      string `json:"status"`
	TotalAmount int64  `json:"totalAmount"`
}

type GetOrdersResponse struct {
	Orders []OrderSummaryResponse `json:"orders"`
	Count  int                    `json:"count"`
}
```

### HTTP handler

```go
// internal/interface/http/order_handler.go
package http

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/example/order-service/internal/application/command"
	"github.com/example/order-service/internal/application/query"
	"github.com/example/order-service/internal/domain/order"
)

type OrderHandler struct {
	createOrder *command.CreateOrderHandler
	cancelOrder *command.CancelOrderHandler
	getOrder    *query.GetOrderHandler
	getOrders   *query.GetOrdersHandler
}

func NewOrderHandler(
	createOrder *command.CreateOrderHandler,
	cancelOrder *command.CancelOrderHandler,
	getOrder *query.GetOrderHandler,
	getOrders *query.GetOrdersHandler,
) *OrderHandler {
	return &OrderHandler{createOrder: createOrder, cancelOrder: cancelOrder, getOrder: getOrder, getOrders: getOrders}
}

func (h *OrderHandler) CreateOrder(w http.ResponseWriter, r *http.Request) {
	// In a real implementation, this value is pulled from the context the auth
	// middleware populated (see authentication.md). This template shows the
	// header example as-is rather than omitting it, but a new domain must
	// always apply the RequireAuth middleware in front so an unvalidated value is never trusted.
	customerID := r.Header.Get("X-Customer-Id")

	var body CreateOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	items := make([]command.CreateOrderItem, len(body.Items))
	for i, it := range body.Items {
		items[i] = command.CreateOrderItem{ItemID: it.ItemID, Name: it.Name, Price: it.Price, Quantity: it.Quantity}
	}

	o, err := h.createOrder.Handle(r.Context(), command.CreateOrderCommand{CustomerID: customerID, Items: items})
	if err != nil {
		writeOrderError(w, err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(toGetOrderResponse(o))
}

func (h *OrderHandler) CancelOrder(w http.ResponseWriter, r *http.Request) {
	customerID := r.Header.Get("X-Customer-Id")
	orderID := r.PathValue("id")

	var body CancelOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.cancelOrder.Handle(r.Context(), command.CancelOrderCommand{
		OrderID: orderID, CustomerID: customerID, Reason: body.Reason,
	}); err != nil {
		writeOrderError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *OrderHandler) GetOrder(w http.ResponseWriter, r *http.Request) {
	customerID := r.Header.Get("X-Customer-Id")
	orderID := r.PathValue("id")

	result, err := h.getOrder.Handle(r.Context(), query.GetOrderQuery{OrderID: orderID, CustomerID: customerID})
	if err != nil {
		writeOrderError(w, err)
		return
	}

	items := make([]OrderItemResponse, len(result.Items))
	for i, it := range result.Items {
		items[i] = OrderItemResponse{ItemID: it.ItemID, Name: it.Name, Price: it.Price, Quantity: it.Quantity}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(GetOrderResponse{
		OrderID:     result.OrderID,
		CustomerID:  result.CustomerID,
		Items:       items,
		Status:      result.Status,
		TotalAmount: result.TotalAmount,
		CreatedAt:   result.CreatedAt,
	})
}

func (h *OrderHandler) GetOrders(w http.ResponseWriter, r *http.Request) {
	customerID := r.Header.Get("X-Customer-Id")
	page, take := parsePagination(r)

	result, err := h.getOrders.Handle(r.Context(), query.GetOrdersQuery{CustomerID: customerID, Page: page, Take: take})
	if err != nil {
		writeOrderError(w, err)
		return
	}

	summaries := make([]OrderSummaryResponse, len(result.Orders))
	for i, o := range result.Orders {
		summaries[i] = OrderSummaryResponse{OrderID: o.OrderID, Status: o.Status, TotalAmount: o.TotalAmount}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(GetOrdersResponse{Orders: summaries, Count: result.Count})
}

// toGetOrderResponse — a helper used for the case where the Domain Aggregate
// must be mapped directly to the Interface DTO (e.g. a response right after
// creation). Since it bypasses the Application Result, it's used only here as
// an exception, unlike the list/lookup paths (GetOrder/GetOrders).
func toGetOrderResponse(o *order.Order) GetOrderResponse {
	items := make([]OrderItemResponse, len(o.Items))
	for i, it := range o.Items {
		items[i] = OrderItemResponse{ItemID: it.ItemID, Name: it.Name, Price: it.Price, Quantity: it.Quantity}
	}
	return GetOrderResponse{
		OrderID:     o.OrderID,
		CustomerID:  o.CustomerID,
		Items:       items,
		Status:      string(o.Status),
		TotalAmount: o.TotalAmount(),
		CreatedAt:   o.CreatedAt,
	}
}

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

func writeOrderError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, order.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, order.ErrEmptyItems),
		errors.Is(err, order.ErrInvalidPrice),
		errors.Is(err, order.ErrInvalidQuantity),
		errors.Is(err, order.ErrAlreadyCancelled),
		errors.Is(err, order.ErrPaidOrderNotCancellable):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

### Router

```go
// internal/interface/http/router.go
package http

import (
	"net/http"

	"github.com/example/order-service/internal/application/command"
	"github.com/example/order-service/internal/application/query"
	"github.com/example/order-service/internal/domain/order"
)

func NewRouter(repo order.Repository) *http.ServeMux {
	createOrderHandler := command.NewCreateOrderHandler(repo)
	cancelOrderHandler := command.NewCancelOrderHandler(repo)
	getOrderHandler := query.NewGetOrderHandler(repo)
	getOrdersHandler := query.NewGetOrdersHandler(repo)

	orderHTTP := NewOrderHandler(createOrderHandler, cancelOrderHandler, getOrderHandler, getOrdersHandler)

	mux := http.NewServeMux()
	mux.HandleFunc("POST /orders", orderHTTP.CreateOrder)
	mux.HandleFunc("POST /orders/{id}/cancel", orderHTTP.CancelOrder)
	mux.HandleFunc("GET /orders/{id}", orderHTTP.GetOrder)
	mux.HandleFunc("GET /orders", orderHTTP.GetOrders)
	return mux
}
```

---

## Dependency assembly — `main.go` (replacing the DI container)

What corresponds to NestJS's `@Module({ providers: [...] })` is, in Go, the constructor chaining called in order in `main.go`. Below is a form that integrates the fail-fast validation from [config.md](architecture/config.md) with the `signal.NotifyContext` + `Shutdown(ctx)` from [graceful-shutdown.md](architecture/graceful-shutdown.md).

```go
// cmd/server/main.go
package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"

	"github.com/example/order-service/internal/application/event"
	"github.com/example/order-service/internal/infrastructure/notification"
	"github.com/example/order-service/internal/infrastructure/outbox"
	"github.com/example/order-service/internal/infrastructure/persistence"
	httphandler "github.com/example/order-service/internal/interface/http"
)

func main() {
	// 1. load config + fail-fast validation (config.md)
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		log.Fatal("config: DATABASE_URL is required")
	}
	queueURL := os.Getenv("SQS_DOMAIN_EVENT_QUEUE_URL")
	if queueURL == "" {
		log.Fatal("config: SQS_DOMAIN_EVENT_QUEUE_URL is required")
	}

	// 2. connect infrastructure
	db, err := sql.Open("postgres", databaseURL)
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	// 3. assemble Infrastructure — constructor chaining, no DI container (module-pattern.md)
	notifier := notification.NewService()
	outboxWriter := outbox.NewWriter()
	sqsClient := outbox.NewSQSClient()
	orderRepo := persistence.NewOrderRepository(db, outboxWriter)

	// This handlers map is used by outbox.Consumer to look up the handler by
	// the eventType of a message received from SQS — the Command Handler
	// never references this map at all (synchronous draining is prohibited, see domain-events.md).
	outboxHandlers := map[string]outbox.Handler{
		"OrderPlaced":    event.NewOrderPlacedHandler(notifier).Handle,
		"OrderCancelled": event.NewOrderCancelledHandler(notifier).Handle,
	}
	outboxPoller := outbox.NewPoller(db, sqsClient, queueURL)
	outboxConsumer := outbox.NewConsumer(sqsClient, queueURL, outboxHandlers)

	// 4. assemble Interface
	mux := httphandler.NewRouter(orderRepo)
	srv := &http.Server{Addr: ":8080", Handler: mux}

	// 5. signal-based graceful shutdown (graceful-shutdown.md) — not just the
	// HTTP server, the Poller/Consumer background loops are also stopped by this same ctx.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		log.Printf("listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()

	// Outbox → SQS publish (Poller) and SQS → EventHandler receive (Consumer)
	// run independently on their own tick, unrelated to HTTP requests — the
	// Command Handler never references either at all (synchronous draining is
	// prohibited, see domain-events.md). Both stop themselves once ctx is canceled.
	go outboxPoller.Run(ctx)
	go outboxConsumer.Run(ctx)

	<-ctx.Done()
	log.Println("shutdown signal received")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("graceful shutdown failed: %v", err)
	}
	log.Println("server stopped")
}
```

The dependency assembly order exactly matches the dependency direction: `db` (the lowest level) → `notifier`/`outboxWriter`/`sqsClient`/`orderRepo`/`outboxPoller`/`outboxConsumer` (Infrastructure) → `mux` (Interface, which internally assembles the Application Handlers too) → `srv` (the server). Reordering any single step would reference a variable not yet declared, making the build itself fail — the Go compiler effectively enforces the assembly order.

---

## Migration

```sql
-- migrations/0001_init.sql
CREATE TABLE orders (
  id           VARCHAR(32)  PRIMARY KEY,   -- common.NewID() — 32-character hex with hyphens removed
  customer_id  VARCHAR(32)  NOT NULL,
  status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at   TIMESTAMP    NULL
);

CREATE TABLE order_items (
  order_id  VARCHAR(32)  NOT NULL REFERENCES orders(id),
  item_id   VARCHAR(32)  NOT NULL,
  name      VARCHAR(200) NOT NULL,
  price     BIGINT       NOT NULL,
  quantity  INT          NOT NULL,
  PRIMARY KEY (order_id, item_id)
);
```

---

## Documents to consult when extending this template

This template is a minimal implementation sized for a single domain, single repository. Depending on real service requirements, introduce the additional patterns below — each document explains the target implementation and the criteria for adopting it.

| When it becomes necessary | Pattern to introduce | Reference document |
|---|---|---|
| When notification/event publishing becomes an important side effect that must not be lost | Outbox (record the event in the same transaction as the Repository save) + Poller/Consumer (asynchronous publish/consume) | [domain-events.md](architecture/domain-events.md) |
| When a second Aggregate needs to be bundled into one transaction | `context.Context`-based `database.WithTx` | [persistence.md](architecture/persistence.md) |
| When the read model needs to be split into a separate store | A separate Query interface in `application/query/` | [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md) |
| When real authentication is needed (the current example trusts the header as-is) | JWT + `net/http` middleware | [authentication.md](architecture/authentication.md) |
| When a periodic batch/cleanup job is needed | A `time.Ticker` Scheduler + Task Outbox | [scheduling.md](architecture/scheduling.md) |
| When another Bounded Context needs to be called | The Adapter pattern | [cross-domain.md](architecture/cross-domain.md) |
| When traffic grows and abuse prevention is needed | A `golang.org/x/time/rate` token-bucket middleware | [rate-limiting.md](architecture/rate-limiting.md) |
| When production deployment is needed | A multi-stage Dockerfile, Secrets Manager, structured logging | [container.md](architecture/container.md), [secret-manager.md](architecture/secret-manager.md), [observability.md](architecture/observability.md) |
| When tests need to be filled in | Table-driven Domain tests, stub-based Application tests, testcontainers-go E2E | [testing.md](architecture/testing.md) |
