# 코딩 컨벤션 (Go)

이 문서는 루트 [conventions.md](../../../docs/conventions.md)의 프레임워크 무관 규칙(REST API 설계, 커밋/브랜치, Rate Limiting 원칙, Repository 메서드 네이밍)을 Go 구현체에 맞게 구체화한다. 근거 코드는 모두 `implementations/go/examples/`(Account 도메인)와 `implementations/go/docs/architecture/*.md`에서 가져왔다.

## 1. 파일 네이밍 규칙

- 모든 파일명: `snake_case.go`
- 패키지명: 소문자 단일 단어, 언더스코어/캐멀케이스 없음, 디렉토리명과 일치 — `package account`, `package persistence`, `package command`
- Aggregate Root: `<aggregate-root>.go` (domain 레이어) — `account.go`
- Entity: `<entity>.go` (domain 레이어) — `transaction.go`
- Value Object: `<value-object>.go` (domain 레이어) — `money.go`
- 상태/열거형 성격의 Value Object: `<domain>_status.go` — `account_status.go`
- Domain Event: `events.go`에 한 파일로 모은다 (도메인 이벤트 종류가 많지 않은 단일 Aggregate 규모에서는 이벤트별 파일 분리보다 하나로 모으는 쪽이 실용적이다. 이벤트 종류가 늘어나 파일이 비대해지면 `<event-name>.go`로 분리하는 것도 가능하다)
- sentinel error: `errors.go`에 한 파일로 모은다 — `var ErrXxx = errors.New(...)` 선언들
- Repository 인터페이스: `repository.go` (domain 레이어, 시그니처만)
- Repository 구현체: `<aggregate>_repository.go` (infrastructure 레이어) — `account_repository.go`
- CommandHandler: `<verb>_<noun>_handler.go` (`application/command/`에 배치) — `create_account_handler.go`, `deposit_handler.go`
- QueryHandler: `<verb>_<noun>_handler.go` (`application/query/`에 배치) — `get_account_handler.go`
- Result DTO: `result.go` (`application/query/`에 배치, 여러 Result 구조체를 한 파일에 모은다)
- Technical Service 인터페이스(알림, 시크릿, 스토리지 등): 사용하는 쪽 application 패키지에 `<concern>.go`로 정의 — `notifier.go`
- Technical Service 구현체: infrastructure의 해당 concern 하위 패키지에 `service.go` — `notification/service.go`
- HTTP 핸들러: `<domain>_handler.go` (`interface/http/`에 배치) — `account_handler.go`
- HTTP DTO: `dto.go` (`interface/http/`에 배치)
- 라우터/조립: `router.go` (`interface/http/`에 배치)
- Scheduler: `<domain>_<concern>_scheduler.go` (infrastructure 레이어) — `account_cleanup_scheduler.go`
- Task Consumer: `<domain>_task_consumer.go` (interface 레이어)
- 미들웨어: `<concern>_middleware.go` (`interface/http/middleware/`에 배치) — `auth_middleware.go`, `correlation_middleware.go`
- 설정: `<concern>_config.go` (`infrastructure/config/`에 배치) — `database_config.go`, `jwt_config.go`
- 공용 순수 유틸: `internal/common/<concern>.go` — `id.go`
- 진입점: `cmd/server/main.go`

---

## 2. 타입 및 식별자 네이밍 규칙

- 타입명(struct/interface): `PascalCase` — `Account`, `Money`, `Repository`
- 공개 함수/메서드: `PascalCase` — `New`, `Deposit`, `FindByID`, `Handle`
- 비공개 함수/메서드: `camelCase` — `newTransaction`, `describe`, `querier`
- Aggregate Root: 도메인 명사 — `Account`
- Entity: 도메인 명사 — `Transaction`
- Value Object: 도메인 개념 — `Money`
- Domain Event: 과거형 — `AccountCreated`, `MoneyDeposited`, `AccountSuspended`
- sentinel error: `ErrXxx` — `ErrNotFound`, `ErrInsufficientBalance`, `ErrWithdrawRequiresActiveAccount`
- Repository 인터페이스: `Repository` (패키지명이 이미 도메인을 나타내므로 `account.Repository`로 충분하다 — `AccountRepository`처럼 중복하지 않는다)
- Repository 구현체: `<Aggregate>Repository` — `AccountRepository` (패키지명이 `persistence`이므로 `persistence.AccountRepository`)
- CommandHandler: `<Verb><Noun>Handler` — `CreateAccountHandler`, `DepositHandler`
- QueryHandler: `<Verb><Noun>Handler` — `GetAccountHandler`, `GetTransactionsHandler`
- Command: `<Verb><Noun>Command` — `CreateAccountCommand`, `DepositCommand`
- Query: `<Verb><Noun>Query` — `GetAccountQuery`, `GetTransactionsQuery`
- Result: `<Verb><Noun>Result` — `GetAccountResult`, `GetTransactionsResult`
- 생성자 함수: `New<Type>` — `NewAccountRepository`, `NewDepositHandler`, `NewRouter`
- 인터페이스 이름은 동사+er보다 역할 명사를 우선한다 — `Repository`, `Notifier`, `SecretService` (TypeScript의 `<X>Adapter`, `<X>Service`도 이 원칙과 같은 계열이다)
- 인터페이스는 만족하는 쪽이 아니라 **사용하는 쪽** 패키지에 정의한다 — `command.Notifier`는 `command` 패키지가 필요로 하는 최소 시그니처만 선언하고, 이를 구현하는 `notification.Service`는 그 존재조차 몰라도 된다

---

## 3. 인터페이스와 구현체 분리 — 컴파일 타임 검증

Go에는 NestJS의 `abstract class` + DI 토큰 등록에 대응하는 개념이 없다. domain(또는 사용하는 쪽 application) 패키지에 `interface` 타입을 선언하고, infrastructure 패키지에 구현 struct를 두는 것으로 동일한 역할을 한다.

```go
// internal/domain/account/repository.go — 인터페이스만, 구현 없음
type Repository interface {
	FindByID(ctx context.Context, accountID, ownerID string) (*Account, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Account, int, error)
	Save(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
```

```go
// internal/infrastructure/persistence/account_repository.go — 구현체
type AccountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) *AccountRepository {
	return &AccountRepository{db: db}
}

// 컴파일 타임 인터페이스 충족 검증 — 반드시 작성한다.
// 메서드 시그니처가 하나라도 어긋나면 여기서 컴파일이 실패한다.
var _ account.Repository = (*AccountRepository)(nil)
```

**규칙:**
- Repository, Adapter, Technical Service(Notifier, SecretService, StorageService 등) 구현체에는 항상 `var _ Interface = (*Impl)(nil)`을 둔다.
- 이 한 줄이 없어도 프로그램은 똑같이 동작하지만, 리팩토링으로 구현체가 인터페이스를 실수로 만족하지 못하게 되는 것을 컴파일 시점에 잡아준다 — 런타임까지 갈 필요가 없다.
- Application Handler는 생성자 파라미터를 인터페이스 타입으로 선언한다(`repo account.Repository`, 구체 타입 `*persistence.AccountRepository`가 아니다). `main.go`/`router.go`가 구체 타입 값을 넘기면 Go의 구조적 타이핑이 자동으로 만족 여부를 검사한다 — 별도의 "바인딩 등록" 단계가 없다.

---

## 4. Go 타이핑 및 도메인 모델링 패턴

### 에러는 반환값으로 — 예외 없음

```go
// 올바른 방식
func (a *Account) Withdraw(amount int64) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	// ...
}
```

Go에는 예외가 없다. "즉시 throw"는 "즉시 `return 제로값, err`"로 표현된다. 호출부는 반드시 `if err != nil`을 확인한다.

### `context.Context`는 항상 첫 인자

```go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) { ... }
func (r *AccountRepository) FindByID(ctx context.Context, accountID, ownerID string) (*account.Account, error) { ... }
```

Repository, Handler, Adapter, Technical Service 등 레이어 경계를 넘는 모든 함수의 첫 인자는 `context.Context`다. 취소·데드라인·(구현 시) Correlation ID·트랜잭션이 이 인자로 명시적으로 전파된다.

### Aggregate — 포인터 리시버로 상태 변경

```go
func (a *Account) Deposit(amount int64) (Transaction, error) { ... } // (a *Account) — 상태를 바꾸므로 포인터 리시버
```

### Value Object — 값 리시버로 불변성 유지

```go
func (m Money) Add(other Money) (Money, error) { ... } // (m Money) — 값 리시버, 항상 새 값 반환
func (m Money) Equals(other Money) bool { ... }
```

값 리시버(`(m Money)`)를 쓰는 것이 의도적이다. 포인터 리시버를 쓰면 메서드 내부에서 원본을 변형할 수 있게 되어 불변 객체의 의미가 흐려진다.

### 생성과 복원의 분리 — `New` / `Reconstitute`

```go
func New(ownerID, email, currency string) *Account { /* ID 새로 발급, 이벤트 발행 */ }
func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	/* 이벤트 없이 상태만 복원 */
}
```

`New(...)`의 파라미터 목록에 ID가 없다는 것 자체가 "클라이언트가 제공한 ID를 받지 않는다"는 규칙을 코드로 강제한다.

### nullable — zero value 우선, 필요할 때만 포인터

Go에는 TypeScript의 `T | null` / `T | undefined` 구분이 없다. 이 저장소는 zero value(`""`, `0`, `nil` 슬라이스)를 "값 없음"으로 쓰는 것을 기본으로 한다.

```go
// FindAll의 동적 필터 — zero value가 "조건 없음"을 의미
if q.AccountID != "" {
	where = append(where, fmt.Sprintf("id = $%d", i))
	args = append(args, q.AccountID)
	i++
}
```

DB의 진짜 NULL과 "값이 설정되지 않음"을 구분해야 하는 필드(예: 선택적 종료일)는 포인터(`*time.Time`)로 명시적으로 표현한다. 어느 쪽을 쓸지는 필드마다 의도적으로 판단한다 — 무조건 포인터로 감싸지 않는다.

### `any` 사용 최소화

`any`(= `interface{}`)는 정말 임의 타입을 받아야 하는 경우(JSON payload 등)로 한정한다. 도메인 값, Command/Query/Result 필드는 항상 구체 타입 또는 명확한 인터페이스로 선언한다.

### enum 대응 — `type Status string` + 상수 그룹

```go
// internal/domain/account/account_status.go
type Status string

const (
	StatusActive    Status = "ACTIVE"
	StatusSuspended Status = "SUSPENDED"
	StatusClosed    Status = "CLOSED"
)
```

TypeScript의 `enum`에 해당하는 것은 Go의 `type X string` + `const` 그룹이다. 매직 스트링을 직접 비교하지 않고 항상 이 상수를 통해 비교한다.

---

## 5. REST API 엔드포인트 설계 규칙

원칙은 루트 [conventions.md](../../../docs/conventions.md) 섹션 1과 동일하다 — URL은 복수 명사 리소스, HTTP 메서드가 행위를 표현한다.

### URL 구조 — 리소스 중심, 복수 명사

```
// 올바른 방식
GET    /accounts                    계좌 목록 조회
GET    /accounts/{id}                계좌 단건 조회
POST   /accounts                    계좌 개설
POST   /accounts/{id}/deposit        입금
POST   /accounts/{id}/withdraw       출금
POST   /accounts/{id}/suspend        정지
POST   /accounts/{id}/reactivate     재개
POST   /accounts/{id}/close          종료
GET    /accounts/{id}/transactions   거래 내역 조회

// 잘못된 방식
GET    /getAccounts        동사를 URL에 넣지 않는다
POST   /createAccount      동사를 URL에 넣지 않는다
GET    /account/{id}       단수형 사용 금지 — 항상 복수형
```

### 라우팅 — `net/http`의 method+path 패턴 (Go 1.22+)

```go
// internal/interface/http/router.go
mux := http.NewServeMux()
mux.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
mux.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
mux.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
```

`r.PathValue("id")`로 경로 변수를 꺼낸다. 별도 라우팅 프레임워크(gorilla/mux 등) 없이 표준 라이브러리만으로 method+path 매칭이 가능하다(Go 1.22+).

### HTTP 메서드와 응답 코드

| 메서드 | 용도 | 성공 코드 | 응답 바디 |
|--------|------|----------|----------|
| `GET` | 리소스 조회 | 200 OK | 있음 |
| `POST` | 리소스 생성/행위 실행 | 201 Created | 있음(생성 결과) 또는 없음(행위성 POST) |
| `PUT` | 리소스 전체 수정 | 200 OK | 있음 |
| `PATCH` | 리소스 부분 수정 | 200 OK | 있음 |
| `DELETE` | 리소스 삭제 | 204 No Content | 없음 |

```go
w.WriteHeader(http.StatusCreated)  // POST 성공
w.WriteHeader(http.StatusNoContent) // 상태 변경류 POST(suspend, close 등) 성공 — 본문 없음
```

### 목록 조회 — 페이지네이션과 필터링

```
GET /accounts?page=0&take=20&status=active
```

```go
// internal/interface/http/account_handler.go
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
```

- `page`: 0부터 시작. `take`: 페이지 크기.
- 프레임워크의 자동 DTO 바인딩이 없으므로 `r.URL.Query()`에서 직접 파싱한다.
- 파싱 실패(잘못된 쿼리 파라미터) 시 기본값으로 흡수할지, 400으로 거부할지는 엔드포인트마다 의도적으로 정한다 — 이 저장소의 예시는 관대한 파싱(기본값 흡수)을 택했다.

### 목록 응답 — 도메인 복수형 키 + count

```go
// internal/interface/http/dto.go
type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"`
	Count        int                          `json:"count"`
}
```

`result`/`data`/`items` 같은 범용 키 대신 도메인 명사의 복수형을 쓴다. `Count`는 페이지 크기가 아니라 필터 적용 후 전체 건수다.

### 단건 응답 — 범용 래퍼 없음

```go
// internal/interface/http/dto.go
type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	OwnerID   string        `json:"ownerId"`
	Balance   MoneyResponse `json:"balance"`
	Status    string        `json:"status"`
	CreatedAt time.Time     `json:"createdAt"`
}
```

`{ success: true, data: {...} }` 같은 래퍼를 씌우지 않는다. 정상/에러 구분은 HTTP 상태 코드가 담당한다.

---

## 6. 메서드/생성자 네이밍 및 구성

### CommandHandler / QueryHandler — 공통 시그니처

```go
func (h *XxxHandler) Handle(ctx context.Context, cmd/query X) (결과, error)
```

모든 Handler가 `Handle` 메서드 하나로 유스케이스를 완결한다. `@nestjs/cqrs`의 CommandBus/QueryBus 같은 런타임 라우팅 계층은 두지 않는다 — Handler를 필드로 직접 보유해 호출한다(컴파일 타임에 타입이 이미 확정되어 있으므로).

### 생성자 함수 — `New<Type>(의존성...) *Type`

```go
func NewDepositHandler(repo account.Repository, notifier Notifier) *DepositHandler {
	return &DepositHandler{repo: repo, notifier: notifier}
}
```

DI 컨테이너가 없으므로 의존성은 항상 생성자 함수의 파라미터로 명시적으로 전달한다. 새 의존성이 필요하면 생성자 파라미터를 추가하고 호출부(`main.go`/`router.go`)를 고친다.

### Repository 메서드 네이밍

- 단건 조회: `FindByID(ctx, id, ownerID) (*T, error)`
- 목록 조회: `FindAll(ctx, query) ([]*T, int, error)`
- 저장(생성/수정 겸용, upsert): `Save(ctx, *T) error`
- `update<Noun>` 성격의 메서드는 두지 않는다 — 상태 변경은 항상 Aggregate 도메인 메서드 호출 후 `Save`로 반영한다.

> 루트 [conventions.md](../../../docs/conventions.md) 섹션 5는 `find<Noun>s` 하나로 통일하고 단건은 `take: 1`로 흉내내라고 규정하지만, 이 저장소의 Go 예제는 단건 조회를 `FindByID`로 분리하는 쪽을 택했다 — 소유자 검증(`ownerID` 일치)이 단건 조회의 핵심 관심사라 명시적 시그니처가 더 읽기 쉽다는 판단이다. 새 도메인을 작성할 때 root 방식(`Find` 단일 메서드)과 이 방식(`FindByID` 분리) 중 하나를 선택할 수 있으나, 한 저장소 안에서는 일관성을 유지한다.

### struct 멤버/파일 구성 순서

1. 필드 선언(struct)
2. 생성자 함수(`New...`)
3. exported 메서드(비즈니스 로직, 조회, 등)
4. unexported 메서드(내부 헬퍼)

---

## 7. import 구성 패턴

### 3그룹 순서 — `goimports`가 자동 정렬

```go
// 1. 표준 라이브러리
import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

// (또는 표준 라이브러리 + 서드파티 + 내부 패키지를 하나의 import() 블록 안에서 빈 줄로 구분)
import (
	"database/sql"
	"log"
	"os"

	_ "github.com/lib/pq" // 2. 서드파티 (blank import — 드라이버 등록용)

	"github.com/example/account-service/internal/infrastructure/persistence" // 3. 내부 패키지
	httphandler "github.com/example/account-service/internal/interface/http"
)
```

- **그룹 순서**: 표준 라이브러리 → 서드파티 → 내부 패키지. 그룹 사이는 빈 줄로 구분한다.
- **그룹 내부**: 알파벳 순 — `goimports` 실행 결과를 그대로 따른다(수작업으로 정렬 상태를 흉내내지 않는다).
- **alias**: 패키지명이 겹치거나(`http` 표준 라이브러리 vs `interface/http`) 의미가 불분명할 때만 사용한다 — `httphandler "github.com/example/account-service/internal/interface/http"`.
- **blank import**: DB 드라이버 등록처럼 부작용만 필요한 경우 `_ "github.com/lib/pq"`로 표기하고 서드파티 그룹에 둔다.
- Go에는 TypeScript의 `@/` alias나 상대경로 import 문제 자체가 없다 — 모듈 경로(`github.com/example/account-service/...`)가 항상 절대 경로다. 다만 `internal/`은 그 상위 디렉토리 바깥에서 import할 수 없다는 컴파일러 강제 규칙이 있다.
- Domain 레이어 파일은 `internal/infrastructure/...`, `internal/interface/...`, `internal/application/...`를 import하지 않는다 — import 그래프가 곧 의존 방향이므로, 이 규칙 위반은 대개 순환 의존으로 이어져 `go build`가 즉시 실패한다.

---

## 8. 에러 처리 패턴

### sentinel error로 타입화

```go
// internal/domain/account/errors.go
var (
	ErrNotFound                      = errors.New("account not found")
	ErrInvalidAmount                 = errors.New("amount must be greater than zero")
	ErrWithdrawRequiresActiveAccount = errors.New("account must be active to withdraw")
	ErrInsufficientBalance           = errors.New("insufficient balance")
)
```

TypeScript의 `<Domain>ErrorMessage`/`<Domain>ErrorCode` enum 두 개를 합친 역할을 sentinel error 변수 하나가 겸한다. `errors.Is(err, account.ErrNotFound)`는 변수의 아이덴티티를 비교하므로 문자열 오타로 매핑이 깨질 수 없다.

- 메시지는 소문자로 시작하고 마침표 등 구두점을 찍지 않는다(다른 에러 메시지에 이어붙여도 문장이 어색하지 않도록 하는 Go 표준 컨벤션).

### `%w`로 래핑 — 컨텍스트 추가

```go
a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
if err != nil {
	return nil, fmt.Errorf("deposit: %w", err)
}
```

- 이미 sentinel error인 경우 불필요하게 다시 래핑하지 않는다.
- DB 드라이버 에러 같은 "원인 불명 에러"는 `fmt.Errorf("<작업 설명>: %w", err)`로 래핑해 어느 계층의 어떤 작업이 실패했는지 추적 가능하게 한다.

### Interface 레이어 — `errors.Is`로 HTTP 상태 코드 매핑

```go
func writeAccountError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, account.ErrInvalidAmount),
		errors.Is(err, account.ErrInsufficientBalance):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

에러를 HTTP 상태 코드로 변환하는 책임은 Interface 레이어에만 있다. 매핑에 없는 에러는 500 + 일반 메시지로 처리해 내부 구현 세부사항이 새어나가지 않게 한다.

---

## 9. 로거 패턴

### `log/slog` — 구조화 로깅

```go
slog.InfoContext(ctx, "notification email sent",
	"event_type", eventType,
	"recipient", content.recipient,
	"ses_message_id", messageID,
)

slog.ErrorContext(ctx, "notification email failed",
	"event_type", eventType,
	"error", err,
)
```

- 필드 키는 snake_case로 명시적으로 쓴다(`event_type`, `ses_message_id`) — Go 코드 자체는 camelCase가 관용이지만, 로그 필드 키는 문자열 리터럴이므로 외부 모니터링 연동 규칙(snake_case)을 그대로 따를 수 있다.
- `ctx`를 받는 `InfoContext`/`ErrorContext`를 사용해 Correlation ID 등 컨텍스트 값과 자연스럽게 연결한다.
- 운영 환경은 `slog.NewJSONHandler`로 JSON 출력을 구성한다.

### 레이어별 로깅 기준

- Domain 레이어: 로깅하지 않는다. `internal/domain/account/`는 `log`/`slog` import 자체가 없어야 한다.
- Application 레이어: 비즈니스 이벤트, 외부 호출 결과를 로깅한다.
- Infrastructure 레이어: 외부 연동 실패/재시도를 로깅한다.
- Interface 레이어: 요청 처리 결과와 예기치 못한 에러(500)를 로깅한다.

---

## 10. 주석 스타일

- **exported 식별자에는 Go doc comment를 단다** — 식별자 이름으로 시작하는 `//` 주석.

```go
// NewID는 UUID v4에서 하이픈을 제거한 32자리 hex 문자열을 반환한다.
func NewID() string { ... }

// Repository는 Account Aggregate의 영속성 경계를 정의한다.
type Repository interface { ... }
```

- **비즈니스 로직 설명은 인라인 `//` 주석**으로 작성한다 — 팀의 기본 언어(한글)로.
- JSDoc 같은 별도 문서화 포맷은 없다 — Go doc comment 자체가 `go doc`/pkg.go.dev의 문서 생성 표준이므로 이를 그대로 따른다(NestJS의 "JSDoc 금지, `//`만 사용" 규칙과 결이 다르다 — Go는 도구 체인이 doc comment를 전제하므로 exported 식별자에는 반드시 작성한다).
- 긴 Handler 메서드는 섹션 주석으로 논리적 구분을 할 수 있다:

```go
// 1. Repository에서 조회
a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
// ...
// 2. 도메인 메서드에 위임
tx, err := a.Deposit(cmd.Amount)
```

---

## 11. 커밋 메시지 컨벤션

[Conventional Commits](https://www.conventionalcommits.org/) 스펙을 따른다. 언어에 종속되지 않으므로 루트 [conventions.md](../../../docs/conventions.md) 섹션 2와 동일하다.

### 메시지 구조

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### type 목록

| type | 설명 | 예시 |
|------|------|------|
| `feat` | 새로운 기능 추가 | `feat(account): 계좌 정지 기능 추가` |
| `fix` | 버그 수정 | `fix(account): 잔액 계산 오류 수정` |
| `refactor` | 기능 변경 없이 코드 구조 변경 | `refactor(account): Repository 조회 로직 정리` |
| `docs` | 문서만 변경 | `docs: repository-pattern 문서 갱신` |
| `test` | 테스트 추가 또는 수정 | `test(account): 출금 불변식 단위 테스트 추가` |
| `chore` | 빌드, CI, 의존성 등 코드 외적인 작업 | `chore(deps): google/uuid 버전 갱신` |
| `style` | 코드 포맷팅 등 동작에 영향 없는 변경 | `style: gofmt 적용` |
| `perf` | 성능 개선 | `perf(account): 목록 조회 쿼리 인덱스 활용` |

### scope 규칙

- scope는 서비스 도메인명을 사용한다: `account`, `user`, `payment` 등
- 여러 도메인에 걸친 변경이면 scope를 생략하거나 상위 개념을 사용한다
- 코드 외적인 변경은 대상을 scope로 사용한다: `ci`, `deps`, `docker` 등

### description 규칙

- 한글로 작성한다
- 명령형이 아닌 서술형으로 작성한다: "추가", "수정", "제거" (NOT "추가하라", "수정해")
- 첫 글자를 대문자로 시작하지 않는다
- 끝에 마침표를 붙이지 않는다

### BREAKING CHANGE

```
feat(account)!: 계좌 응답 스키마 변경

BREAKING CHANGE: GetAccountResponse에서 balance가 문자열에서 객체로 변경됨
```

### 예시

```
feat(account): 계좌 정지 기능 추가

fix(account): 동시 출금 시 잔액이 음수가 되는 경쟁 조건 수정 (#42)

refactor(account): FindByID의 소유자 검증 로직을 Repository로 이동

Handler에서 소유자 일치 여부를 직접 비교하던 로직을 Repository 쿼리
조건으로 옮겨 권한 없는 계좌 조회 시 정보 노출을 줄인다.

test(account): 출금 시 통화 불일치 에러 케이스 테스트 추가
```

---

## 12. 브랜치 및 PR 컨벤션

언어에 종속되지 않으므로 루트 [conventions.md](../../../docs/conventions.md) 섹션 3과 동일하다.

### 브랜치 네이밍 — Conventional Branch

```
<type>/<scope>-<short-description>
```

| type | 용도 | 예시 |
|------|------|------|
| `feat` | 새 기능 개발 | `feat/account-suspend` |
| `fix` | 버그 수정 | `fix/account-balance-race` |
| `refactor` | 리팩터링 | `refactor/account-repository-query` |
| `docs` | 문서 변경 | `docs/go-repository-pattern` |
| `test` | 테스트 추가/수정 | `test/account-withdraw-invariant` |
| `chore` | 빌드, CI, 의존성 | `chore/ci-go-test-workflow` |

**규칙:**
- 모든 단어는 `kebab-case`로 작성한다.
- `main` 브랜치에서 분기한다.
- `main` 브랜치에 직접 commit/push하지 않는다.

### PR 워크플로우

```
1. main에서 새 브랜치 생성
   git checkout main && git pull origin main
   git checkout -b <type>/<scope>-<short-description>

2. 작업 후 commit (Conventional Commits 형식)
   git add <files>
   git commit -m "<type>(<scope>): <description>"

3. 원격에 push
   git push -u origin <branch-name>

4. main 브랜치로 PR 생성
   gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR 제목/본문

```
feat(account): 계좌 정지 기능 추가
```

```markdown
## Summary
- 변경 사항을 1~3줄로 요약

## Test plan
- [ ] go test ./internal/... 통과
- [ ] go test ./test/... (testcontainers) 통과
- [ ] ./harness.sh . 통과
```

### 머지 전략

- **Squash and merge**를 기본으로 사용한다.
- 머지 후 원격 브랜치는 자동 삭제한다.

---

## 13. 테스트 패턴

### 단위 테스트 — Domain 레이어 (table-driven)

Domain 레이어 단위 테스트는 프레임워크 없이 순수 Go `testing` 패키지로 작성하고, 외부 테스트 패키지(`package account_test`)로 공개 API만 사용해 테스트한다.

```go
// internal/domain/account/account_test.go
package account_test

import (
	"errors"
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestAccount_Withdraw(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *account.Account
		amount  int64
		wantErr error
	}{
		{
			name:    "정지된_계좌에서_출금하면_에러",
			setup:   func() *account.Account { a := account.New("owner-1", "a@example.com", "KRW"); _ = a.Suspend(); return a },
			amount:  1000,
			wantErr: account.ErrWithdrawRequiresActiveAccount,
		},
		{
			name:    "잔액보다_큰_금액을_출금하면_에러",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			amount:  1000,
			wantErr: account.ErrInsufficientBalance,
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
```

### 단위 테스트 — Application Handler (수동 stub)

```go
// internal/application/command/deposit_handler_test.go
package command_test

type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindByID(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
	return s.findByIDFn(ctx, accountID, ownerID)
}
func (s *stubRepository) Save(ctx context.Context, a *account.Account) error { return s.saveFn(ctx, a) }
// FindAll, FindTransactions 등 나머지 인터페이스 메서드도 최소 구현으로 채운다

func TestDepositHandler_Handle_AccountNotFound(t *testing.T) {
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewDepositHandler(repo, &stubNotifier{})

	_, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: "missing", Amount: 1000})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}
```

- Repository는 반드시 인터페이스 타입으로 mock한다 — 구체 타입 mock 금지.
- 인터페이스가 메서드 3~4개 수준으로 작을 때는 수작업 stub이 mocking 프레임워크보다 읽기/디버깅하기 쉽다는 것이 Go 커뮤니티의 일반적인 선호다. `testify/mock` 등을 쓸 수도 있지만 필수는 아니다.
- 비즈니스 로직(잔액 계산, 상태 전이 검증)은 Domain 단위 테스트에서 이미 검증했으므로 여기서는 반복하지 않는다 — Handler가 Repository/Notifier를 올바른 순서로 호출하는지만 본다.

### E2E 테스트 — testcontainers-go

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
	)
	// ... LocalStack 컨테이너 기동, migrations/*.sql 순서대로 실행 ...
	return m.Run()
}
```

- 운영 DB가 아닌 컨테이너를 사용한다(testcontainers-go가 Postgres + LocalStack을 직접 기동).
- `TestMain`에서 컨테이너를 한 번만 띄우고 여러 테스트가 공유한다 — 테스트 간 데이터 격리를 위해 각 테스트가 고유한 `ownerID`/`AccountID`를 생성해 충돌을 피한다.
- HTTP 요청부터 DB/외부 연동까지 실제 경로를 우회 없이 통과한다.

### 테스트 파일 배치

```
internal/
  domain/account/
    account_test.go              ← Domain 단위 테스트 (package account_test, 소스 옆)
  application/command/
    deposit_handler_test.go      ← Application 단위 테스트 (package command_test, 소스 옆)
test/
  account_e2e_test.go            ← E2E 테스트 (별도 디렉토리, 모듈 루트의 test/)
```

Go 컨벤션은 단위 테스트를 소스와 같은 디렉토리에 두는 것이다(`_test.go` 접미사만으로 `go build`에서 자동 제외된다). 여러 패키지를 조립해 컨테이너까지 띄우는 E2E만 별도 `test/` 디렉토리로 분리한다.

### 테스트 네이밍 패턴

```
TestXxx_When<조건>_Then<기대결과>
예: TestDepositHandler_Handle_AccountNotFound
    TestAccount_Withdraw (서브테스트 이름에 조건/기대결과를 한글로 명시: "정지된_계좌에서_출금하면_에러")
```

### 실행

```bash
go test ./internal/...        # Domain + Application 단위 테스트 (빠름, 외부 의존성 없음)
go test ./test/...            # E2E (Docker 필요, testcontainers-go가 컨테이너를 직접 기동)
go test ./...                 # 전체
```
