# CQRS 패턴 (Go)

CQRS(Command Query Responsibility Segregation)의 원칙은 루트 [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md)를 따른다: 쓰기(Command)와 읽기(Query)의 책임을 분리한다. Go에는 `@nestjs/cqrs`의 `CommandBus`/`QueryBus` 같은 프레임워크가 없다 — 이 저장소는 그런 인프라 없이 **plain struct + `Handle` 메서드**만으로 CQRS 경량 적용을 구현하며, 이 저장소의 실제 코드가 곧 이 패턴의 레퍼런스다.

---

## 적용 기준

| 상황 | 권장 |
|---|---|
| 유스케이스마다 별도 Handler로 관심사를 명확히 나누고 싶을 때 | 이 문서의 Handler 패턴 |
| Command Bus/Query Bus로 런타임 라우팅이 필요할 만큼 유스케이스가 많고 동적일 때 | Go에서는 흔치 않음 — 아래 "Bus가 필요 없는 이유" 참조 |

---

## 디렉토리 구조 (이 저장소의 실제 구조)

```
internal/
  domain/
    account/
      account.go              ← Aggregate Root
      repository.go            ← Repository(Command) + Query interface
      events.go                ← Domain Event
  application/
    command/
      create_account_handler.go    ← CreateAccountCommand + CreateAccountHandler
      deposit_handler.go
      withdraw_handler.go
      suspend_account_handler.go
      reactivate_account_handler.go
      close_account_handler.go
      event_relay.go                ← OutboxRelay 인터페이스 (Technical Service)
    query/
      get_account_handler.go        ← GetAccountQuery + GetAccountHandler
      get_transactions_handler.go
      result.go                     ← Result 구조체
    event/
      account_created_event_handler.go   ← Outbox가 드레인한 이벤트를 처리 (domain-events.md 참고)
      money_deposited_event_handler.go
      money_withdrawn_event_handler.go
      account_suspended_event_handler.go
      account_reactivated_event_handler.go
      account_closed_event_handler.go
  interface/
    http/
      account_handler.go            ← HTTP 진입점 — Command/Query Handler를 직접 호출
      router.go                     ← 조립 + 라우팅
```

---

## Command와 CommandHandler

Command는 쓰기 요청을 나타내는 불변 데이터 구조체다. CommandHandler는 Repository(와 필요하면 OutboxRelay 같은 Technical Service)를 의존성으로 갖고 `Handle` 메서드 하나로 유스케이스를 완결한다.

```go
// internal/application/command/create_account_handler.go
type CreateAccountCommand struct {
	RequesterID string
	Email       string
	Currency    string
}

type CreateAccountHandler struct {
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewCreateAccountHandler(repo account.Repository, outboxRelay OutboxRelay) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency) // 비즈니스 로직은 Aggregate에 위임
	if err := h.repo.Save(ctx, a); err != nil {                // Aggregate 저장 + Outbox 적재, 한 트랜잭션
		return nil, err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {  // 커밋 직후 동기적으로 Outbox 드레인
		return nil, err
	}
	return a, nil
}
```

같은 패턴이 `deposit_handler.go`, `withdraw_handler.go`, `suspend_account_handler.go`, `reactivate_account_handler.go`, `close_account_handler.go`에 반복된다 — 각 Handler는 정확히 하나의 유스케이스만 책임진다.

**모든 CommandHandler의 공통 시그니처:**

```go
func (h *XxxHandler) Handle(ctx context.Context, cmd XxxCommand) (결과, error)
```

`context.Context`를 항상 첫 인자로 받는 것은 Go 표준 컨벤션이다 — 취소 전파, 타임아웃, (향후) 트랜잭션/Correlation ID 전파의 통로가 된다.

---

## Query와 QueryHandler

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
	a, err := h.repo.FindByID(ctx, q.AccountID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return &GetAccountResult{ /* Aggregate → Result 매핑 */ }, nil
}
```

### root와의 대응 — 별도 읽기 전용 인터페이스(`account.Query`)

root [layer-architecture.md](../../../../docs/architecture/layer-architecture.md)는 Query Service가 Command와는 별도의 읽기 전용 인터페이스를 쓰라고 규정한다. 이 저장소는 `internal/domain/account/repository.go`에서 이를 두 인터페이스로 표현한다:

```go
// internal/domain/account/repository.go
type Query interface {
	FindByID(ctx context.Context, accountID, ownerID string) (*Account, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}

type Repository interface {
	Query
	Save(ctx context.Context, account *Account) error
}
```

`GetAccountHandler`/`GetTransactionsHandler`는 `account.Query`만 의존성으로 받는다 — 타입 시스템 수준에서 `Save`를 호출할 수 없다. `internal/infrastructure/persistence/account_repository.go`의 `AccountRepository`는 두 인터페이스를 각각 별도로 구현할 필요가 없다: Go의 interface는 구조적 타이핑이므로, `FindByID`/`FindAll`/`FindTransactions`/`Save` 네 메서드를 갖춘 concrete struct 하나가 `Repository`와 `Query`를 동시에 만족한다(`var _ account.Query = (*AccountRepository)(nil)` 컴파일 타임 검증도 함께 둔다). `internal/interface/http/router.go`는 여전히 단일 `accountRepo` 인스턴스를 조립해 Command Handler에는 `account.Repository`로, Query Handler에는 `account.Query`로 전달한다 — `Repository`가 `Query`를 embed하므로 별도 어댑터 없이 그대로 넘길 수 있다.

읽기 모델을 별도 저장소(read replica, 캐시, 검색 인덱스 등)로 분리해야 하는 시점이 오면, `internal/infrastructure/`에 `Query`만 구현하는 별도 read-only 구현체를 추가로 두는 방향으로 확장한다 — 인터페이스가 이미 분리되어 있으므로 Query Handler 쪽 코드는 바뀌지 않는다.

---

## Interface 레이어 — Handler를 직접 호출 (Bus 없음)

```go
// internal/interface/http/account_handler.go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	// ...
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{ /* ... */ })
	// ...
}
```

### Bus가 필요 없는 이유

`CommandBus.execute(command)`처럼 커맨드 타입으로 런타임에 핸들러를 조회하는 계층은, 리플렉션이나 데코레이터 메타데이터에 의존하는 동적 언어/프레임워크에서 유용하다. Go는:

- **컴파일 타임에 정확한 타입**을 알고 있다 — `h.createAccount.Handle(ctx, cmd)`처럼 Handler를 필드로 직접 보유하면 컴파일러가 타입을 검증해준다. Bus를 거치면 오히려 `interface{}`/리플렉션이 끼어들어 타입 안전성이 떨어진다.
- **`main.go`에서 생성자로 이미 조립**되어 있다 — Handler 인스턴스를 찾기 위한 서비스 로케이터가 필요 없다([layer-architecture.md](layer-architecture.md)의 "DI 컨테이너 없음" 참조).

그래서 `internal/interface/http/router.go`가 Bus 대신 각 Handler를 직접 생성자로 조립하고, `AccountHandler`가 이들을 필드로 보유한다:

```go
// internal/interface/http/router.go
func NewRouter(repo account.Repository, outboxRelay command.OutboxRelay) *http.ServeMux {
	createAccountHandler := command.NewCreateAccountHandler(repo, outboxRelay)
	depositHandler := command.NewDepositHandler(repo, outboxRelay)
	// ...
	getAccountHandler := query.NewGetAccountHandler(repo)
	// ...

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler, /* ... */)
	// ...
}
```

---

## EventHandler — Domain Event 후속 처리

이 저장소는 root가 요구하는 Outbox 기반 EventHandler를 그대로 구현한다: `internal/infrastructure/persistence/account_repository.go`의 `Save`가 Aggregate 상태와 Outbox 행을 같은 트랜잭션으로 커밋하고, Command Handler는 그 직후 `OutboxRelay.ProcessPending(ctx)`를 동기 호출해 드레인한다. `event_type`별 후속 처리는 `internal/application/event/*_event_handler.go`가 맡는다. 상세는 [domain-events.md](domain-events.md)에서 다룬다.

```go
// internal/application/event/account_created_event_handler.go
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt) // outbox.Relay가 이 Handle을 event_type="AccountCreated" 행마다 호출한다
}
```

---

## 기존 아키텍처와의 비교

| | 기본 아키텍처 (Service 분리) | 이 저장소의 Handler 기반 CQRS |
|---|---|---|
| 쓰기 진입점 | `XxxCommandService.method()` | `XxxHandler.Handle(ctx, cmd)` |
| 읽기 진입점 | `XxxQueryService.method()` | `XxxHandler.Handle(ctx, query)` |
| 라우팅 | Service 직접 호출 | Handler를 필드로 보유, 직접 호출 (Bus 없음) |
| 유스케이스 단위 | Service 메서드 | Handler 구조체 (파일 1개 = 유스케이스 1개) |
| 읽기/쓰기 분리 | Service 클래스 분리 | Handler 분리 + `Repository`/`Query` 인터페이스 분리 |

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, DI 없는 조립
- [domain-events.md](domain-events.md) — EventHandler, Outbox 패턴과 현재 코드의 편차
- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스 설계
- [directory-structure.md](directory-structure.md) — `application/command`·`application/query` 배치
