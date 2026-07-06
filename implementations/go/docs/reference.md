# 실전 구현 템플릿 (Go)

전체 도메인 하나(`Order`)를 본 아키텍처로 구현한 예시다. 새 도메인을 추가할 때 이 템플릿을 복사해서 시작한다. 이 저장소의 `examples/`(Account 도메인)도 동일한 구조를 실제로 쓰고 있으니 함께 참고한다.

> 이 템플릿은 [aggregate-id.md](architecture/aggregate-id.md)가 규정하는 올바른 ID 형식(하이픈 제거 32자리 hex)과 [error-handling.md](architecture/error-handling.md)의 sentinel error 패턴을 그대로 따르는 **목표 상태(target-state)** 코드다. `examples/`의 Account 도메인 코드 자체에는 몇 가지 알려진 격차(uuid 하이픈 미제거, Outbox 미구현 등)가 있는데, 이 문서는 그 격차를 재현하지 않는다 — 각 격차와 이유는 해당 `architecture/*.md` 문서에 명시되어 있다.

---

## 디렉토리 구조

```
cmd/
  server/
    main.go                          ← 진입점, 의존성 조립

internal/
  common/
    id.go                             ← common.NewID() — 하이픈 제거 32자리 hex

  domain/
    order/
      order.go                        ← Aggregate Root (New/Reconstitute + 도메인 메서드)
      order_item.go                   ← Value Object
      order_status.go                 ← Status 타입 (enum 대응)
      events.go                       ← DomainEvent 인터페이스 + 이벤트 구조체
      errors.go                       ← sentinel error
      repository.go                   ← Repository 인터페이스 + FindQuery

  application/
    command/
      create_order_handler.go         ← CreateOrderCommand + CreateOrderHandler
      cancel_order_handler.go         ← CancelOrderCommand + CancelOrderHandler
      notifier.go                     ← Notifier 인터페이스 (Technical Service 포트)
    query/
      get_order_handler.go            ← GetOrderQuery + GetOrderHandler
      get_orders_handler.go           ← GetOrdersQuery + GetOrdersHandler
      result.go                       ← Result 구조체들

  infrastructure/
    persistence/
      order_repository.go             ← order.Repository 구현체 (database/sql)
    notification/
      service.go                      ← command.Notifier 구현체

  interface/
    http/
      router.go                       ← net/http 라우팅 + 의존성 조립 보조
      order_handler.go                ← HTTP 핸들러
      dto.go                          ← 요청/응답 DTO

migrations/
  0001_init.sql

test/
  order_e2e_test.go
```

여러 도메인이 추가되면 `internal/domain/<domain>/`, `internal/application/command/<domain>_*.go`, `internal/infrastructure/persistence/<domain>_repository.go`처럼 도메인별로 파일이 늘어난다. Go는 레이어를 최상위, 도메인을 그 하위에 두는 구조를 쓴다([directory-structure.md](architecture/directory-structure.md) 참고) — NestJS가 도메인을 최상위에 두는 것과 반대다.

---

## Domain 레이어

### Aggregate Root

```go
// internal/domain/order/order.go — 프레임워크 무의존, 표준 라이브러리 + 최소 의존성만 import
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
	events     []DomainEvent // 소문자 시작 — 패키지 밖에서 직접 접근 불가
}

// New는 신규 주문을 생성한다. 최소 1개 이상의 주문 항목이 필요하다.
// ID는 여기서 새로 발급한다 — 파라미터 목록에 ID가 없다는 것 자체가
// "클라이언트가 제공한 ID를 받지 않는다"는 규칙을 코드로 강제한다.
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

// Reconstitute는 DB에서 읽은 값으로 Order를 복원한다. 이벤트를 발행하지 않는다.
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

// Cancel은 주문을 취소한다. 불변식은 여기서만 검증한다 —
// Application Handler는 이 메서드를 호출할 뿐 조건을 직접 검사하지 않는다.
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

// TotalAmount는 주문 항목 금액의 합계를 계산한다.
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
// internal/domain/order/order_item.go — 불변 객체, 값 리시버로 메서드 정의
package order

type OrderItem struct {
	ItemID   string
	Name     string
	Price    int64
	Quantity int
}

// NewOrderItem은 불변식(가격/수량이 0보다 큼)을 검증한 뒤 OrderItem을 반환한다.
func NewOrderItem(itemID, name string, price int64, quantity int) (OrderItem, error) {
	if price <= 0 {
		return OrderItem{}, ErrInvalidPrice
	}
	if quantity <= 0 {
		return OrderItem{}, ErrInvalidQuantity
	}
	return OrderItem{ItemID: itemID, Name: name, Price: price, Quantity: quantity}, nil
}

// Equals는 속성 기반 동등성을 비교한다 — 식별자가 아니라 값으로 비교하는 Value Object다.
func (i OrderItem) Equals(other OrderItem) bool {
	return i.ItemID == other.ItemID &&
		i.Name == other.Name &&
		i.Price == other.Price &&
		i.Quantity == other.Quantity
}
```

### 상태 (enum 대응)

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

// DomainEvent는 Order 도메인 이벤트가 공유하는 마커 인터페이스다.
// Go에는 union 타입이 없으므로 빈 메서드로 "이 이벤트들 중 하나"라는 관계를 표현한다.
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

### sentinel error

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

### Repository 인터페이스

```go
// internal/domain/order/repository.go — 인터페이스만, 구현 없음
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

### 공용 ID 유틸

```go
// internal/common/id.go
package common

import (
	"strings"

	"github.com/google/uuid"
)

// NewID는 UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환한다.
func NewID() string {
	return strings.ReplaceAll(uuid.NewString(), "-", "")
}
```

---

## Application 레이어

### Technical Service 포트 — Notifier

```go
// internal/application/command/notifier.go
package command

import (
	"context"

	"github.com/example/order-service/internal/domain/order"
)

// Notifier는 Order 도메인 이벤트에 대응하는 알림을 보내는 Technical Service 포트다.
// 인터페이스는 사용하는 쪽(command 패키지)에 두고, 구현체는 infrastructure에 둔다.
type Notifier interface {
	Notify(ctx context.Context, event order.DomainEvent)
}
```

### CommandHandler

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
	repo     order.Repository
	notifier Notifier
}

func NewCreateOrderHandler(repo order.Repository, notifier Notifier) *CreateOrderHandler {
	return &CreateOrderHandler{repo: repo, notifier: notifier}
}

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) (*order.Order, error) {
	items := make([]order.OrderItem, 0, len(cmd.Items))
	for _, i := range cmd.Items {
		item, err := order.NewOrderItem(i.ItemID, i.Name, i.Price, i.Quantity) // 불변식 검증은 Domain에서
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	o, err := order.New(cmd.CustomerID, items)
	if err != nil {
		return nil, err
	}
	if err := h.repo.Save(ctx, o); err != nil {
		return nil, fmt.Errorf("create order: %w", err)
	}
	notify(ctx, h.notifier, o)
	return o, nil
}

// notify는 Aggregate가 수집한 이벤트를 Notifier로 전달하고 비운다.
// 현재는 Repository 저장 직후 동기 호출한다 — Outbox로 전환하는 목표 패턴은
// domain-events.md 참고(이벤트가 유실되면 안 되는 중요한 부가효과라면 반드시 전환한다).
func notify(ctx context.Context, notifier Notifier, o *order.Order) {
	for _, event := range o.DomainEvents() {
		notifier.Notify(ctx, event)
	}
	o.ClearEvents()
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
	repo     order.Repository
	notifier Notifier
}

func NewCancelOrderHandler(repo order.Repository, notifier Notifier) *CancelOrderHandler {
	return &CancelOrderHandler{repo: repo, notifier: notifier}
}

func (h *CancelOrderHandler) Handle(ctx context.Context, cmd CancelOrderCommand) error {
	// 1. Repository에서 조회
	o, err := h.repo.FindByID(ctx, cmd.OrderID, cmd.CustomerID)
	if err != nil {
		return fmt.Errorf("cancel order: %w", err)
	}

	// 2. 비즈니스 규칙은 Aggregate 내부에서 검증 — Handler는 조율만 한다
	if err := o.Cancel(cmd.Reason); err != nil {
		return err
	}

	// 3. Repository로 저장
	if err := h.repo.Save(ctx, o); err != nil {
		return fmt.Errorf("cancel order: %w", err)
	}

	// 4. 부가 효과(알림)
	notify(ctx, h.notifier, o)
	return nil
}
```

### QueryHandler와 Result

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

	// 도메인 Aggregate를 그대로 반환하지 않고 응답 전용 Result로 매핑한다.
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

> 이 저장소의 Account 예제와 동일하게, 위 Query Handler는 별도 Query 인터페이스 없이 Command와 동일한 `order.Repository`를 재사용한다. 읽기 모델을 별도 저장소(read replica, 검색 인덱스 등)로 분리해야 하는 시점이 오면 [layer-architecture.md](architecture/layer-architecture.md)가 제시하는 `Query` 인터페이스 분리 패턴을 도입한다.

---

## Infrastructure 레이어

### Repository 구현체

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
)

type OrderRepository struct {
	db *sql.DB
}

func NewOrderRepository(db *sql.DB) *OrderRepository {
	return &OrderRepository{db: db}
}

// 컴파일 타임 인터페이스 충족 검증 — 메서드 시그니처가 하나라도 어긋나면 여기서 컴파일이 실패한다.
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

	// DB row를 도메인 Aggregate로 변환 — row를 그대로 반환하지 않는다.
	return order.Reconstitute(id, custID, items, order.Status(status), createdAt, updatedAt), nil
}

func (r *OrderRepository) FindAll(ctx context.Context, q order.FindQuery) ([]*order.Order, int, error) {
	args := []any{}
	where := []string{"deleted_at IS NULL"}
	i := 1

	// 값이 있을 때만 조건 추가 — zero value가 "조건 없음"을 의미한다.
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

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save order: %w", err)
	}
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

### Technical Service 구현체 — Notifier

```go
// internal/infrastructure/notification/service.go
package notification

import (
	"context"
	"log/slog"

	"github.com/example/order-service/internal/application/command"
	"github.com/example/order-service/internal/domain/order"
)

type Service struct{}

func NewService() *Service {
	return &Service{}
}

var _ command.Notifier = (*Service)(nil)

// Notify는 도메인 이벤트에 대응하는 알림을 처리한다. 이 템플릿은 구조화 로깅만 하는
// 최소 구현이다 — 실제 이메일/SMS 발송이 필요하면 이 저장소의 SES 클라이언트 관용구
// (notification/ses_client.go)를 참고하고, 실패해도 되는 부가 기능이 아니라면
// domain-events.md의 Outbox 패턴으로 전환해 dual-write를 피한다.
func (s *Service) Notify(ctx context.Context, event order.DomainEvent) {
	switch e := event.(type) {
	case order.OrderPlaced:
		slog.InfoContext(ctx, "order placed", "order_id", e.OrderID, "total_amount", e.TotalAmount)
	case order.OrderCancelled:
		slog.InfoContext(ctx, "order cancelled", "order_id", e.OrderID, "reason", e.Reason)
	}
}
```

---

## Interface 레이어

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

### HTTP 핸들러

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
	// 실제 구현에서는 이 값을 인증 미들웨어가 채운 context에서 꺼낸다 (authentication.md 참고).
	// 이 템플릿은 헤더 예시를 생략하지 않고 그대로 보여주되, 검증되지 않은 값을 신뢰하지 않도록
	// 새 도메인에서는 RequireAuth 미들웨어를 반드시 앞에 적용한다.
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

// toGetOrderResponse — Domain Aggregate를 Interface DTO로 직접 매핑해야 하는 경우
// (예: 생성 직후 응답)에 쓰는 헬퍼. Application Result를 거치지 않는 지름길이므로
// 목록/조회 경로(GetOrder/GetOrders)와 달리 여기서만 예외적으로 사용한다.
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

### 라우터

```go
// internal/interface/http/router.go
package http

import (
	"net/http"

	"github.com/example/order-service/internal/application/command"
	"github.com/example/order-service/internal/application/query"
	"github.com/example/order-service/internal/domain/order"
)

func NewRouter(repo order.Repository, notifier command.Notifier) *http.ServeMux {
	createOrderHandler := command.NewCreateOrderHandler(repo, notifier)
	cancelOrderHandler := command.NewCancelOrderHandler(repo, notifier)
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

## 의존성 조립 — `main.go` (DI 컨테이너 대체)

NestJS의 `@Module({ providers: [...] })`에 대응하는 것은, Go에서는 `main.go`에서 순서대로 호출하는 생성자 체이닝이다. 아래는 [config.md](architecture/config.md)의 fail-fast 검증과 [graceful-shutdown.md](architecture/graceful-shutdown.md)의 `signal.NotifyContext` + `Shutdown(ctx)`를 통합한 형태다.

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

	"github.com/example/order-service/internal/infrastructure/notification"
	"github.com/example/order-service/internal/infrastructure/persistence"
	httphandler "github.com/example/order-service/internal/interface/http"
)

func main() {
	// 1. 설정 로드 + fail-fast 검증 (config.md)
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		log.Fatal("config: DATABASE_URL is required")
	}

	// 2. 인프라 연결
	db, err := sql.Open("postgres", databaseURL)
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	// 3. Infrastructure 조립 — DI 컨테이너 없이 생성자 체이닝 (module-pattern.md)
	orderRepo := persistence.NewOrderRepository(db)
	notifier := notification.NewService()

	// 4. Interface 조립
	mux := httphandler.NewRouter(orderRepo, notifier)
	srv := &http.Server{Addr: ":8080", Handler: mux}

	// 5. 시그널 기반 graceful shutdown (graceful-shutdown.md)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		log.Printf("listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()

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

의존성 조립 순서는 의존 방향과 정확히 일치한다: `db`(가장 하위) → `orderRepo`/`notifier`(Infrastructure) → `mux`(Interface, 내부에서 Application Handler까지 조립) → `srv`(서버). 어느 한 단계라도 순서를 바꾸면 아직 선언되지 않은 변수를 참조하게 되어 컴파일 자체가 실패한다 — Go 컴파일러가 조립 순서를 강제하는 셈이다.

---

## 마이그레이션

```sql
-- migrations/0001_init.sql
CREATE TABLE orders (
  id           VARCHAR(32)  PRIMARY KEY,   -- common.NewID() — 하이픈 제거 32자리 hex
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

## 이 템플릿을 확장할 때 참고할 문서

이 템플릿은 단일 도메인·단일 저장소 규모에 맞는 최소 구현이다. 실제 서비스 요구사항에 따라 아래 패턴을 추가로 도입한다 — 각 문서가 목표 구현과 도입 기준을 설명한다.

| 필요해지는 시점 | 도입할 패턴 | 참고 문서 |
|---|---|---|
| 알림/이벤트 발행이 유실되면 안 되는 중요한 부가효과가 될 때 | Outbox + Relay (Repository 저장과 같은 트랜잭션에 이벤트 적재) | [domain-events.md](architecture/domain-events.md) |
| 두 번째 Aggregate와 하나의 트랜잭션으로 묶어야 할 때 | `context.Context` 기반 `database.WithTx` | [persistence.md](architecture/persistence.md) |
| 읽기 모델을 별도 저장소로 분리해야 할 때 | `application/query/`에 별도 Query 인터페이스 | [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md) |
| 실제 인증이 필요할 때 (현재 예제는 헤더를 그대로 신뢰) | JWT + `net/http` 미들웨어 | [authentication.md](architecture/authentication.md) |
| 주기적 배치/정리 작업이 필요할 때 | `time.Ticker` Scheduler + Task Outbox | [scheduling.md](architecture/scheduling.md) |
| 다른 Bounded Context를 호출해야 할 때 | Adapter 패턴 | [cross-domain.md](architecture/cross-domain.md) |
| 트래픽이 늘어 남용 방지가 필요할 때 | `golang.org/x/time/rate` 토큰 버킷 미들웨어 | [rate-limiting.md](architecture/rate-limiting.md) |
| 운영 배포가 필요할 때 | 멀티스테이지 Dockerfile, Secrets Manager, 구조화 로깅 | [container.md](architecture/container.md), [secret-manager.md](architecture/secret-manager.md), [observability.md](architecture/observability.md) |
| 테스트를 채워야 할 때 | table-driven Domain 테스트, stub 기반 Application 테스트, testcontainers-go E2E | [testing.md](architecture/testing.md) |
