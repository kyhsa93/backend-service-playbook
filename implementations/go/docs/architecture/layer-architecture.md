# 레이어 아키텍처 (Go)

원칙은 루트 [layer-architecture.md](../../../../docs/architecture/layer-architecture.md)를 따른다: `Interface → Application → Domain`, `Infrastructure`는 `Domain`의 인터페이스를 구현하며 의존성을 역전시킨다. Go에는 NestJS의 `@Injectable`/DI 컨테이너가 없으므로, 이 저장소는 **인터페이스를 domain 패키지에 선언하고, 구현체를 infrastructure 패키지에 두고, `cmd/server/main.go`에서 생성자 함수로 손수 연결**하는 방식으로 정확히 동일한 의존 방향을 만든다.

---

## 의존 방향

```
internal/interface/http   →  internal/application/{command,query}  →  internal/domain/account
                                                                            ↑ (interface 정의)
                                                              internal/infrastructure/persistence (구현)
```

Go의 `import` 그래프가 곧 의존 방향이다 — `internal/domain/account`는 `internal/infrastructure/...`를 import하지 않는다(실제로 `account.go`, `repository.go`를 보면 `database/sql`이나 `net/http` import가 전혀 없다). `internal/infrastructure/persistence/account_repository.go`가 반대로 `internal/domain/account`를 import해서 그 인터페이스를 구현한다 — 이것이 Go에서의 의존성 역전이다.

---

## Domain 레이어 — `internal/domain/account/`

프레임워크 무의존 순수 Go 코드다. import를 보면 검증할 수 있다: `account.go`는 `time`, `github.com/google/uuid`만 쓴다(표준 라이브러리 + 최소 의존성).

- **Aggregate Root** — `Account` (`account.go`): 도메인 메서드(`Deposit`, `Withdraw`, `Suspend`, `Reactivate`, `Close`) 내부에서만 불변식을 검증하고 상태를 바꾼다.
- **Entity** — `Transaction` (`transaction.go`): `TransactionID`로 식별.
- **Value Object** — `Money` (`money.go`): `Amount`+`Currency` 조합으로 `Equals()` 판단.
- **Domain Event** — `AccountCreated` 등 (`events.go`): 과거형 이름, `DomainEvent` 인터페이스.
- **Repository 인터페이스** — `Repository` (`repository.go`): 구현은 여기 없고 시그니처만.

```go
// internal/domain/account/repository.go — 인터페이스만, 구현 없음
type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	SaveAccount(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

→ 상세는 [tactical-ddd.md](tactical-ddd.md).

---

## Application 레이어 — `internal/application/{command,query}/`

Go에는 `@nestjs/cqrs`의 `CommandBus`/`QueryBus`가 없다 — 대신 **구조체 + `Handle` 메서드**가 root의 Command/Query Service 역할을 겸한다([cqrs-pattern.md](cqrs-pattern.md) 참고). 조율만 하고 비즈니스 로직은 Aggregate에 위임하는 원칙은 동일하다.

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)  // 1. Repository에서 조회
	if err != nil {
		return nil, fmt.Errorf("deposit: %w", err)
	}
	tx, err := a.Deposit(cmd.Amount)                                // 2. 도메인 메서드에 위임
	if err != nil {
		return nil, err
	}
	if err := h.repo.SaveAccount(ctx, a); err != nil {               // 3. Repository로 저장 (Outbox 행도 같은 트랜잭션)
		return nil, err
	}
	// 저장 후 곧바로 반환한다 — 부가 효과(알림)는 독립적으로 주기 실행되는
	// outbox.Poller/outbox.Consumer가 비동기로 처리한다(domain-events.md 참고).
	return &tx, nil
}
```

root는 Command Service와 Query Service를 **서로 다른 인터페이스**(Repository vs Query)로 분리하라고 요구한다. 이 저장소는 `internal/domain/account/repository.go`에 `Repository`(Command, `SaveAccount` 포함)와 `Query`(읽기 메서드만)를 별도 인터페이스로 정의해 이 원칙을 따른다 — Query Handler(`query/get_account_handler.go`, `query/get_transactions_handler.go`)는 `Query`만 의존성으로 받으므로 타입 시스템 수준에서 `SaveAccount`를 호출할 수 없다:

```go
// internal/domain/account/repository.go
type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}

type Repository interface {
	Query
	SaveAccount(ctx context.Context, account *Account) error
}

// internal/application/query/get_account_handler.go
type GetAccountHandler struct {
	repo account.Query  // 읽기 전용 인터페이스만 의존
}
```

`internal/infrastructure/persistence/account_repository.go`의 `AccountRepository`는 두 인터페이스의 구현체를 따로 둘 필요가 없다 — Go interface는 구조적 타이핑이므로, `Save`를 포함한 3개 메서드(`FindAccounts`/`FindTransactions`/`Save`)를 갖춘 concrete struct 하나가 `Repository`와 `Query`를 동시에 만족한다. `router.go`는 여전히 단일 `accountRepo` 인스턴스를 조립해 Command Handler에는 `account.Repository`로, Query Handler에는 `account.Query`로 전달한다 — `Repository`가 `Query`를 embed하므로 별도 어댑터 없이 넘길 수 있다. 읽기 모델을 별도 저장소(read replica, 캐시, 검색 인덱스 등)로 분리해야 하는 시점이 오면 `Query`만 구현하는 read-only 구현체를 추가하는 방향으로 확장한다.

---

## Interface 레이어 — `internal/interface/http/`

HTTP 요청을 받아 Command/Query로 변환하고 에러를 HTTP 상태 코드로 변환한다(`account_handler.go`). Interface DTO(`dto.go`)는 root가 요구하는 "Application 객체의 thin wrapper" 원칙을 Go 방식으로 구현한다 — TypeScript의 `class X extends Y {}` 상속 대신, Go는 상속이 없으므로 **필드를 그대로 재선언**한 독립 구조체를 쓰고 핸들러에서 명시적으로 매핑한다:

```go
// dto.go — Application Result를 감싸는 thin wrapper (상속 대신 필드 복제 + 매핑)
type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	Balance   MoneyResponse `json:"balance"`
	// ...
}

// account_handler.go — 매핑은 핸들러가 명시적으로 수행
json.NewEncoder(w).Encode(GetAccountResponse{
	AccountID: result.AccountID,
	Balance:   MoneyResponse{Amount: result.Balance.Amount, Currency: result.Balance.Currency},
	// ...
})
```

---

## Infrastructure 레이어 — `internal/infrastructure/`

Domain의 인터페이스를 구현하는 유일한 레이어다. `persistence/account_repository.go`는 컴파일 타임에 인터페이스 충족을 강제한다:

```go
var _ account.Repository = (*AccountRepository)(nil)
```

이 한 줄이 실패하면(메서드 시그니처가 하나라도 안 맞으면) **컴파일 자체가 안 된다**. TypeScript의 구조적 타이핑이나 `implements` 키워드가 컴파일러 수준에서 자동으로 강제하는 것을, Go는 이 관용구로 명시적으로 얻는다 — root의 어떤 문서도 이 메커니즘을 언급하지 않는 이유는 다른 언어들은 별도 관용구 없이 컴파일러가 대신 해주기 때문이다.

---

## DI 대신 — `cmd/server/main.go`에서 생성자 체이닝

root는 "프레임워크별 DI 연결 방법은 `docs/implementations/` 참조"라고 위임한다. Go의 답은 **DI 컨테이너 없음, 생성자 함수를 손으로 호출**이다.

```go
// cmd/server/main.go
db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
// ...
notifier := notification.NewService(notification.NewSESClient(), db)
outboxWriter := outbox.NewWriter()
sqsClient := outbox.NewSQSClient()
outboxHandlers := map[string]outbox.Handler{ /* ... 이벤트 타입별 핸들러 ... */ }
outboxPoller := outbox.NewPoller(db, sqsClient, queueURL)         // Outbox → SQS 발행 (독립 goroutine)
outboxConsumer := outbox.NewConsumer(sqsClient, queueURL, outboxHandlers) // SQS → Handler 실행 (독립 goroutine)
accountRepo := persistence.NewAccountRepository(db, outboxWriter) // infrastructure 구현체 생성
mux := httphandler.NewRouter(accountRepo)                         // domain 인터페이스 타입으로 주입
```

```go
// internal/interface/http/router.go — 여기서 Application 레이어 조립까지 이어짐
func NewRouter(repo account.Repository) *http.ServeMux {
	depositHandler := command.NewDepositHandler(repo)
	getAccountHandler := query.NewGetAccountHandler(repo)
	// ...
}
```

Command Handler는 `outboxPoller`/`outboxConsumer`를 전혀 참조하지 않는다 — Repository.Save 후 곧바로 반환하고, Outbox → SQS 발행/수신은 `main()`이 별도 goroutine(`go outboxPoller.Run(ctx)`, `go outboxConsumer.Run(ctx)`)으로 독립 실행한다(domain-events.md 참고).

`persistence.NewAccountRepository(db)`가 반환하는 구체 타입(`*AccountRepository`)은 `NewRouter`의 파라미터 타입(`account.Repository` 인터페이스)으로 암묵적으로 만족된다 — Go는 구조적 타이핑이므로 `implements` 선언이 필요 없다. 리플렉션 기반 DI 컨테이너가 하는 일(타입 이름으로 구현체 찾아 연결)을 **컴파일러가 정적으로 확인 가능한 함수 호출**로 대체한 것이 이 저장소의 방식이며, 도메인이 늘어나도 `main.go`에 생성자 호출을 추가하는 것 이상의 복잡도가 생기지 않는다.

---

## Go 트랜잭션 전파 — `context.Context` vs AsyncLocalStorage

root는 여러 Repository를 하나의 트랜잭션으로 묶을 때 컨텍스트-로컬 저장소(Node: AsyncLocalStorage)를 권장한다. Go의 관용적 대응은 `context.Context`에 값으로 담아 전파하는 것이며, `internal/infrastructure/database/`(`WithTx`/`TxFromContext`/`QuerierFrom`/`Manager`)가 실제로 이를 구현한다 — 계좌 간 송금(Transfer)이 출금 계좌+입금 계좌 저장을 하나의 트랜잭션으로 묶어야 하는 첫 실사용처다. `AccountRepository.SaveAccount()`는 앰비언트 트랜잭션이 있으면 참여하고, 없으면(기존 단독 호출부처럼) 스스로 열고 커밋한다. 상세는 [persistence.md](persistence.md) 참고.

---

## 의존 방향은 harness가 자동 검사한다

이 문서가 서술하는 의존 방향은 두 harness 규칙이 정적으로 강제한다:

- `domain-layer-isolation`(`implementations/go/harness/domain_layer_isolation.go`) — `internal/domain/**/*.go`가 `internal/application/`·`internal/infrastructure/`·`internal/interface/` 어느 것도 import하지 않는지, import 경로 세그먼트 기준으로 검사한다(특정 라이브러리 이름 블록리스트가 아니라서 새 패키지가 생겨도 자동으로 커버된다).
- `interface-no-infrastructure`(`implementations/go/harness/interface_no_infrastructure.go`) — `internal/interface/**/*.go`(HTTP 핸들러/라우터)가 `internal/infrastructure/`를 직접 import하지 않는지 검사한다. JWT 검증처럼 infrastructure 구현체가 필요한 기술적 관심사는, 사용하는 곳(`interface/http/middleware` 등) 근처에 작은 인터페이스를 선언해 구조적 타이핑으로 받고 구체 타입 조립은 `cmd/server/main.go`(합성 루트)로 미룬다(`authentication.md`의 `TokenIssuer`/`PasswordHasher`와 동일한 패턴 — `middleware.TokenVerifier`가 이 방식을 그대로 따른다).

새 도메인을 추가하며 이 의존 방향을 실수로 어겨도 `harness.sh`가 FAIL로 잡아낸다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 레이어 내부 설계
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Handler 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [persistence.md](persistence.md) — 트랜잭션 전파의 실제 현황과 격차
- [domain-events.md](domain-events.md) — Application 레이어에서 이벤트 처리
