# Go DDD 구현 가이드

이 문서는 [backend-service-playbook](../../../docs/architecture/)의 설계 원칙을 Go로 구현할 때의 언어별 상세를 담는다.
설계 원칙(레이어 역할, Repository 패턴, CQRS 등)은 루트 docs를 참조한다.

---

## 디렉토리 구조

```
cmd/
  server/
    main.go               ← 진입점 (DI 조립, 서버 시작)
internal/
  domain/
    order/
      order.go            ← Aggregate Root
      order_item.go       ← Value Object
      order_cancelled.go  ← Domain Event
      repository.go       ← OrderRepository interface
  application/
    command/
      create_order_handler.go
      cancel_order_handler.go
    query/
      get_order_handler.go
      get_orders_handler.go
  infrastructure/
    persistence/
      order_repository.go ← OrderRepository 구현체
  interface/
    http/
      order_handler.go    ← HTTP 핸들러 (net/http 또는 gin/echo)
      dto.go              ← 요청·응답 DTO
```

> `internal/` 패키지를 사용해 외부 모듈에서 직접 import를 방지한다.

---

## 파일명·패키지 네이밍

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명 | `snake_case.go` | `order_repository.go` |
| 패키지명 | 소문자 단어 (언더스코어 없음) | `package order`, `package persistence` |
| 타입명 | `PascalCase` | `Order`, `OrderRepository` |
| 함수·메서드 | `camelCase` (내부) / `PascalCase` (공개) | `cancel()`, `Cancel()` |
| 상수·에러 | `ErrXxx` 형식 | `ErrOrderNotFound` |

패키지명은 디렉토리명과 일치시킨다. 여러 단어는 디렉토리를 중첩해 분리한다 (`application/command/` → `package command`).

---

## Repository 패턴

TypeScript의 `abstract class` 대신 Go `interface`를 사용한다.

```go
// internal/domain/order/repository.go
package order

import "context"

type Repository interface {
    FindByID(ctx context.Context, orderID string) (*Order, error)
    FindAll(ctx context.Context, query FindQuery) ([]*Order, int, error)
    Save(ctx context.Context, order *Order) error
    Delete(ctx context.Context, orderID string) error
}

type FindQuery struct {
    Page   int
    Take   int
    Status []string
    UserID string
}
```

구현체는 `internal/infrastructure/persistence/`에 위치하며, interface를 컴파일 타임에 검증한다.

```go
// internal/infrastructure/persistence/order_repository.go
package persistence

import "github.com/yourorg/yourapp/internal/domain/order"

type OrderRepository struct {
    db *sql.DB
}

// 컴파일 타임 interface 충족 검증
var _ order.Repository = (*OrderRepository)(nil)

func NewOrderRepository(db *sql.DB) *OrderRepository {
    return &OrderRepository{db: db}
}
```

---

## CQRS 패턴

`@nestjs/cqrs`의 CommandHandler 대신 구조체 + 메서드 방식으로 구현한다.

```go
// internal/application/command/create_order_handler.go
package command

import (
    "context"
    "github.com/yourorg/yourapp/internal/domain/order"
)

type CreateOrderCommand struct {
    UserID string
    Items  []order.Item
}

type CreateOrderHandler struct {
    repo order.Repository
}

func NewCreateOrderHandler(repo order.Repository) *CreateOrderHandler {
    return &CreateOrderHandler{repo: repo}
}

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) error {
    o, err := order.New(cmd.UserID, cmd.Items)
    if err != nil {
        return err
    }
    return h.repo.Save(ctx, o)
}
```

```go
// internal/application/query/get_order_handler.go
package query

import (
    "context"
    "github.com/yourorg/yourapp/internal/domain/order"
)

type GetOrderQuery struct {
    OrderID string
}

type GetOrderResult struct {
    OrderID     string
    Status      string
    TotalAmount int
}

type GetOrderHandler struct {
    repo order.Repository
}

func NewGetOrderHandler(repo order.Repository) *GetOrderHandler {
    return &GetOrderHandler{repo: repo}
}

func (h *GetOrderHandler) Handle(ctx context.Context, q GetOrderQuery) (*GetOrderResult, error) {
    o, err := h.repo.FindByID(ctx, q.OrderID)
    if err != nil {
        return nil, err
    }
    return &GetOrderResult{
        OrderID:     o.OrderID,
        Status:      string(o.Status),
        TotalAmount: o.TotalAmount(),
    }, nil
}
```

---

## 에러 처리

sentinel error + `fmt.Errorf` wrapping 패턴을 사용한다.

```go
// internal/domain/order/errors.go
package order

import "errors"

var (
    ErrNotFound          = errors.New("order not found")
    ErrAlreadyCancelled  = errors.New("order already cancelled")
    ErrPaidNotCancellable = errors.New("paid order cannot be cancelled")
    ErrEmptyItems        = errors.New("order must have at least one item")
    ErrInvalidPrice      = errors.New("item price must be greater than zero")
    ErrInvalidQuantity   = errors.New("item quantity must be greater than zero")
)
```

호출 측에서 `errors.Is`로 타입 판별 후 HTTP 상태 코드를 매핑한다.

```go
// interface/http/order_handler.go
func (h *OrderHandler) GetOrder(w http.ResponseWriter, r *http.Request) {
    result, err := h.getOrderHandler.Handle(r.Context(), query.GetOrderQuery{OrderID: orderID})
    if err != nil {
        switch {
        case errors.Is(err, order.ErrNotFound):
            http.Error(w, err.Error(), http.StatusNotFound)
        default:
            http.Error(w, "internal server error", http.StatusInternalServerError)
        }
        return
    }
    json.NewEncoder(w).Encode(result)
}
```

---

## Soft Delete

TypeScript 예시와 동일하게 `DeletedAt *time.Time` 컬럼을 사용한다. DB 레이어에서 `WHERE deleted_at IS NULL` 조건을 항상 포함한다.

```go
type OrderRow struct {
    OrderID   string
    UserID    string
    Status    string
    CreatedAt time.Time
    UpdatedAt time.Time
    DeletedAt *time.Time  // nil이면 활성, non-nil이면 soft-deleted
}
```

---

## 의존성 주입

Go는 DI 프레임워크 없이 생성자 함수로 조립한다. `cmd/server/main.go`에서 모든 의존성을 직접 연결한다.

```go
// cmd/server/main.go
func main() {
    db := mustConnectDB()

    orderRepo     := persistence.NewOrderRepository(db)
    createHandler := command.NewCreateOrderHandler(orderRepo)
    getHandler    := query.NewGetOrderHandler(orderRepo)

    orderHTTPHandler := http.NewOrderHandler(createHandler, getHandler)

    mux := net_http.NewServeMux()
    mux.HandleFunc("POST /orders", orderHTTPHandler.CreateOrder)
    mux.HandleFunc("GET /orders/{id}", orderHTTPHandler.GetOrder)

    net_http.ListenAndServe(":8080", mux)
}
```
