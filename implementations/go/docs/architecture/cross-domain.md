# 크로스 도메인 호출 패턴 (Go)

> 동기(Adapter) vs 비동기(Integration Event) **선택 기준**은 root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 그대로 따른다. 이 문서는 동기 호출을 선택했을 때 Go로 Adapter 패턴을 어떻게 구현하는지 다룬다.

**이 저장소의 `examples/`는 Account 도메인 하나뿐이라 실제로 크로스 도메인 호출이 필요한 장면이 없다.** 아래 예시는 두 번째 Bounded Context(가상의 User 도메인)가 추가된다고 가정한 **가상 예시**다 — 실제로 존재하는 코드가 아니라, 이 저장소의 실제 관용구(sentinel error, `context.Context` 전파, 컴파일 타임 interface 검증)를 그대로 사용해 "실제로 추가된다면 이렇게 생긴다"를 보여주는 목적으로 작성했다.

---

## 원칙

1. **Application 레이어는 다른 도메인의 Repository/Service를 직접 참조하지 않는다.** 호출이 필요하면 자신의 패키지 안에 필요한 형태로 정의한 인터페이스(Adapter)를 통해서만 접근한다.
2. **인터페이스는 호출하는 쪽(Account 도메인)의 `internal/application/` 아래에** 정의한다 — 호출받는 쪽(User 도메인)의 전체 API가 아니라, 호출하는 쪽이 필요로 하는 최소한의 모양만 선언한다.
3. **구현체는 호출하는 쪽의 `internal/infrastructure/` 아래에** 둔다. 이 구현체가 실제 User 도메인 패키지를 import하고 호출한다 — 의존 방향은 여전히 "Infrastructure → 다른 도메인"이며 Account의 Application/Domain 레이어는 User 도메인을 전혀 모른다.
4. **`var _ Interface = (*Impl)(nil)` 컴파일 타임 검증**을 Adapter 구현체에도 그대로 적용한다([repository-pattern.md](repository-pattern.md)와 동일 관용구).

```
[Account 도메인]                                          [User 도메인 — 가상]
  internal/application/query/
    user_adapter.go (interface)
  internal/application/command/
    create_account_handler.go (UserAdapter 사용)
  internal/infrastructure/user/
    adapter.go (구현체) ────────import───────▶  internal/domain/user (가상)
```

---

## Step 1 — 호출하는 쪽 Application 레이어에 인터페이스 정의

```go
// internal/application/query/user_adapter.go
package query

import "context"

// UserAdapter는 Account 도메인이 필요로 하는 최소한의 User 조회만 노출하는 포트다.
// User 도메인의 전체 API를 알 필요가 없다 — 호출하는 쪽(Account)이 필요한 모양으로 정의한다.
type UserAdapter interface {
	FindUser(ctx context.Context, userID string) (UserSummary, error)
}

// UserSummary는 Account 도메인이 실제로 쓰는 필드만 담은 값이다 — User 도메인의
// 내부 구조(주소, 가입일 등)를 그대로 노출하지 않는다. Anticorruption Layer 역할.
type UserSummary struct {
	UserID string
	Name   string
}
```

인터페이스가 호출받는 쪽(User)이 아니라 **호출하는 쪽(Account)의 패키지에** 있다는 점이 핵심이다. Go의 구조적 타이핑 덕분에 User 도메인은 이 인터페이스의 존재조차 몰라도 된다 — `FindUser(ctx, userID) (UserSummary, error)`를 만족하는 아무 타입이나 이 자리에 꽂을 수 있다.

---

## Step 2 — 호출하는 쪽 Infrastructure 레이어에 구현체 작성

```go
// internal/infrastructure/user/adapter.go
package user

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/application/query"
	userdomain "github.com/example/account-service/internal/domain/user" // 가상의 User 도메인
)

// Adapter는 query.UserAdapter를 만족하는 구현체다. 가상의 User 도메인이 exports하는
// Repository를 호출하고, Account 도메인이 원하는 형태(UserSummary)로 변환한다.
type Adapter struct {
	users userdomain.Repository
}

func NewAdapter(users userdomain.Repository) *Adapter {
	return &Adapter{users: users}
}

func (a *Adapter) FindUser(ctx context.Context, userID string) (query.UserSummary, error) {
	u, err := a.users.FindByID(ctx, userID)
	if err != nil {
		return query.UserSummary{}, fmt.Errorf("find user for account adapter: %w", err)
	}
	return query.UserSummary{UserID: u.ID, Name: u.Name}, nil
}

var _ query.UserAdapter = (*Adapter)(nil) // 컴파일 타임 검증
```

이 구현체는 User 도메인의 응답(가상의 `userdomain.User`)을 Account 도메인이 정의한 `UserSummary`로 변환한다 — User 도메인 내부 모델이 바뀌어도 이 Adapter 하나만 고치면 된다.

> **같은 프로세스(모놀리스)가 아니라 별도 서비스로 배포된 User 도메인을 호출하는 경우**, `Adapter`는 `userdomain.Repository`를 직접 주입받는 대신 HTTP/gRPC 클라이언트를 감싼다(`http.Client` + `context.Context`로 타임아웃 전파). 인터페이스 정의(Step 1)는 그대로 유지되고 Step 2의 구현 내부만 네트워크 호출로 바뀐다 — 호출하는 쪽 Application 레이어는 이 차이를 전혀 몰라도 된다는 것이 Adapter 패턴의 핵심 이점이다.

---

## Step 3 — Application Handler에서 Adapter 사용

```go
// internal/application/command/create_account_handler.go — 가상 확장
type CreateAccountHandler struct {
	repo        account.Repository
	notifier    Notifier
	userAdapter query.UserAdapter // 다른 도메인 호출은 Adapter를 통해서만
}

func NewCreateAccountHandler(repo account.Repository, notifier Notifier, userAdapter query.UserAdapter) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo, notifier: notifier, userAdapter: userAdapter}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	owner, err := h.userAdapter.FindUser(ctx, cmd.OwnerID)
	if err != nil {
		return nil, fmt.Errorf("create account: %w", err)
	}

	a := account.New(owner.UserID, cmd.Email, cmd.Currency)
	if err := h.repo.Save(ctx, a); err != nil {
		return nil, fmt.Errorf("create account: %w", err)
	}
	return a, nil
}
```

Repository 조회와 마찬가지로 `context.Context`가 첫 인자로 전파되어 취소·데드라인·(있다면) Correlation ID가 다른 도메인 호출까지 이어진다.

---

## Step 4 — `main.go`에서 배선

```go
// cmd/server/main.go — 가상 확장 (module-pattern.md 참고)
userRepo := userpersistence.NewRepository(db)      // 가상의 User 도메인 Repository 구현체
userAdapter := user.NewAdapter(userRepo)            // Account 쪽 Adapter

accountRepo := persistence.NewAccountRepository(db)
notifier := notification.NewService(notification.NewSESClient(), db)
createAccountHandler := command.NewCreateAccountHandler(accountRepo, notifier, userAdapter)
```

NestJS는 `OrderModule.imports = [UserModule]` + DI 컨테이너가 이 배선을 런타임에 풀어주지만, Go는 `main.go`(또는 `router.go`)에서 생성자 인자로 명시적으로 전달하는 것이 배선의 전부다. 상세는 [module-pattern.md](module-pattern.md) 참고.

---

## 원칙

- **다른 도메인의 Repository/Service를 Application/Domain 레이어에 직접 주입하지 않는다** — 항상 자신의 패키지에 정의한 Adapter 인터페이스를 거친다.
- **Adapter 인터페이스는 호출하는 쪽이 필요로 하는 최소한의 모양으로** 정의한다 — 호출받는 쪽의 전체 API를 노출하지 않는다.
- **쓰기(상태 변경)는 Adapter로 하지 않는다** — 다른 BC의 상태를 바꿔야 하면 root 원칙대로 Integration Event(비동기)로 전환한다([domain-events.md](domain-events.md)의 Outbox 패턴 참고, 현재 이 저장소에는 미구현).
- **`context.Context`를 그대로 전파**한다 — Adapter 호출도 Repository 호출과 동일하게 취소/데드라인을 존중해야 한다.

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준 (root, 언어 무관)
- [repository-pattern.md](repository-pattern.md) — 인터페이스/구현체 분리와 컴파일 타임 검증 관용구
- [domain-events.md](domain-events.md) — 비동기 선택 시 Outbox 패턴 (목표 구현, 현재 미구현)
- [module-pattern.md](module-pattern.md) — Adapter 배선이 실제로 이루어지는 `main.go` 조립 순서
