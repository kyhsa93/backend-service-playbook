# 테스트 전략 (Go)

원칙은 루트 [testing.md](../../../../docs/architecture/testing.md)를 따른다: Domain 단위 테스트, Application 단위 테스트(Repository mock), E2E 테스트 3단계로 구성한다. **적용 완료** — 3단계 모두 `examples/`에 구현되어 있다(더 이상 gap 아님). 이 문서는 Go 표준 라이브러리 `testing` 패키지와 table-driven test 관용구로 각 계층을 어떻게 작성했는지 제시한다.

| 레이어 | 검증 범위 | 의존성 전략 | 현재 상태 |
|---|---|---|---|
| Domain 단위 테스트 | Aggregate, Value Object | 프레임워크 없음, 순수 함수 호출 | **있음** — `internal/domain/account/account_test.go`, `money_test.go` |
| Application 단위 테스트 | Command/Query Handler | Repository를 수동 stub으로 대체(`stub_test.go`) | **있음** — `create_account_handler_test.go`, `deposit_handler_test.go` |
| E2E 테스트 | HTTP → Handler → Repository → 실제 DB | testcontainers-go (Postgres + LocalStack) | **있음** — `test/account_e2e_test.go`, `test/notification_e2e_test.go` |

---

## Domain 단위 테스트 — 실제 코드

Go 표준 컨벤션대로 소스 옆에 `_test.go`로 둔다(`internal/domain/account/account_test.go`). 외부 의존성이 없으므로 `go test ./internal/domain/...`만으로 밀리초 단위로 끝난다. **table-driven test**가 Go의 표준 스타일이다 — `describe`/`it` 대신 `[]struct{...}` 슬라이스 + `for` 루프 + `t.Run` 서브테스트를 쓴다.

```go
// internal/domain/account/account_test.go — 실제 코드
package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestAccount_Withdraw(t *testing.T) {
	tests := []struct {
		name       string
		setup      func() *account.Account
		amount     int64
		wantErr    error
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
		{
			name:    "0원_이하_출금은_에러",
			setup:   func() *account.Account { a := account.New("owner-1", "a@example.com", "KRW"); _, _ = a.Deposit(5000); return a },
			amount:  0,
			wantErr: account.ErrInvalidAmount,
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

func TestAccount_Deposit_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents() // New()가 이미 AccountCreated를 쌓아두므로 테스트 대상 이벤트만 남기고 초기화

	if _, err := a.Deposit(1000); err != nil {
		t.Fatalf("Deposit() unexpected error: %v", err)
	}

	events := a.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if _, ok := events[0].(account.MoneyDeposited); !ok {
		t.Fatalf("want MoneyDeposited, got %T", events[0])
	}
}
```

**원칙:**
- `package account_test`(외부 테스트 패키지)를 써서 공개 API만으로 테스트한다 — 패키지 내부 비공개 필드에 의존하지 않는지 자연스럽게 검증된다.
- 테스트 픽스처는 `setup func() *account.Account` 클로저로 구성하고 필요한 상태만 조립한다(root의 `createOrder(overrides)` 헬퍼 패턴과 동일한 의도).
- 기대 에러는 `errors.Is`로 비교한다(sentinel error 값 자체를 비교 — 문자열 비교 금지).
- `math/rand`, 시간, 외부 패키지 import 없이 순수 로직만 검증한다.

---

## Application 단위 테스트 — 실제 코드

Repository를 mock으로 대체해 Handler의 조율 로직(에러 전파, Save 호출 여부, notify 호출 여부)만 검증한다. Go에는 mocking 프레임워크가 필수는 아니다 — `account.Repository` 인터페이스를 구현하는 최소 stub 구조체(`stub_test.go`)를 직접 작성하는 것으로 충분하다.

```go
// internal/application/command/deposit_handler_test.go — 실제 코드
package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

// stubRepository는 테스트별로 필요한 동작만 함수 필드로 주입받는 최소 mock이다.
type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindByID(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
	return s.findByIDFn(ctx, accountID, ownerID)
}
func (s *stubRepository) Save(ctx context.Context, a *account.Account) error { return s.saveFn(ctx, a) }
func (s *stubRepository) FindAll(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	return nil, 0, nil
}
func (s *stubRepository) FindTransactions(ctx context.Context, accountID string, page, take int) ([]account.Transaction, int, error) {
	return nil, 0, nil
}

type stubOutboxRelay struct{ processed int }

func (s *stubOutboxRelay) ProcessPending(ctx context.Context) error { s.processed++; return nil }

func TestDepositHandler_Handle_AccountNotFound(t *testing.T) {
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewDepositHandler(repo, &stubOutboxRelay{})

	_, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: "missing", Amount: 1000})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestDepositHandler_Handle_SavesAndDrainsOutbox(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn:     func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	outboxRelay := &stubOutboxRelay{}
	handler := command.NewDepositHandler(repo, outboxRelay)

	if _, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: a.AccountID, Amount: 1000}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !saveCalled {
		t.Fatal("want repo.Save to be called")
	}
	if outboxRelay.processed == 0 {
		t.Fatal("want outboxRelay.ProcessPending to be called at least once")
	}
}
```

`go.mod`에 이미 `github.com/stretchr/testify`가 있으므로(E2E에서 `require` 패키지로 사용 중), 필요하면 `testify/mock`으로 stub을 대체할 수도 있지만 — 인터페이스가 메서드 3~4개 수준으로 작을 때는 위처럼 손으로 쓴 stub이 더 읽기 쉽고 디버깅하기 쉽다는 것이 Go 커뮤니티의 일반적인 선호다.

**원칙:**
- Repository는 반드시 인터페이스 타입(`account.Repository`)으로 mock — 구체 타입 mock 금지(root 원칙과 동일).
- 비즈니스 로직(잔액 계산, 상태 전이 검증)은 이미 Domain 단위 테스트에서 검증했으므로 여기서는 반복하지 않는다 — Handler가 Repository/OutboxRelay를 **올바른 순서로 호출하는지**만 본다.

---

## E2E 테스트 — 이미 구현되어 있음

`test/account_e2e_test.go`가 testcontainers-go로 실제 Postgres + LocalStack(SES) 컨테이너를 띄우고, `httptest.Server`로 실제 HTTP 요청을 보내 전체 경로를 검증한다.

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
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(60*time.Second),
		),
	)
	// ... LocalStack 컨테이너도 유사하게 기동 ...
	// ... migrations/*.sql을 순서대로 읽어 실행 ...
	return m.Run()
}
```

`test/notification_e2e_test.go`는 LocalStack의 SES 디버그 엔드포인트(`/_aws/ses`)를 호출해 실제로 이메일이 "발송"되었는지, 그리고 `sent_emails` 테이블에 기록이 남았는지까지 검증한다 — HTTP 계층부터 인프라 계층까지 실제 경로를 우회 없이 통과한다.

**원칙 준수 확인:**
- 운영 DB가 아닌 컨테이너 사용 — root 원칙과 일치.
- `TestMain`에서 컨테이너를 한 번만 띄우고 여러 테스트가 공유 — 컨테이너 기동 비용을 상쇄. 다만 테스트 간 데이터 격리를 위해 각 테스트가 자체적으로 고유한 `ownerID`/`AccountID`를 생성해 충돌을 피한다(파일 상단 `ownerID`, `otherOwnerID` 상수 및 각 테스트에서의 신규 계좌 생성 패턴).

---

## 테스트 파일 배치

```
internal/
  domain/account/
    account_test.go              ← Domain 단위 테스트 (package account_test, 소스 옆)
  application/command/
    deposit_handler_test.go      ← Application 단위 테스트 (package command_test, 소스 옆)
test/
  account_e2e_test.go            ← E2E 테스트 (별도 디렉토리, 모듈 루트의 test/)
  notification_e2e_test.go
```

Go 컨벤션은 단위 테스트를 소스와 같은 디렉토리에 두는 것이다(`_test.go` 접미사만으로 `go build`에서 제외됨) — root가 예시로 든 `order.spec.ts`를 소스 옆에 두는 것과 동일한 배치 원칙이다. E2E처럼 여러 패키지를 조립해 컨테이너까지 띄우는 테스트만 별도 `test/` 디렉토리로 분리한다.

---

## 실행

```bash
go test ./internal/...        # Domain + Application 단위 테스트 (빠름, 외부 의존성 없음)
go test ./test/...            # E2E (Docker 필요, testcontainers-go가 컨테이너를 직접 기동)
go test ./...                 # 전체
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 단위 테스트 대상(Aggregate 불변식)
- [layer-architecture.md](layer-architecture.md) — Application 레이어 조율 로직(단위 테스트 대상)
- [repository-pattern.md](repository-pattern.md) — mock 대상 인터페이스
- [local-dev.md](local-dev.md) — E2E가 사용하는 것과 동일한 Postgres/LocalStack 구성
