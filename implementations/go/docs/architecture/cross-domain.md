# 크로스 도메인 호출 패턴 (Go)

> 동기(Adapter) vs 비동기(Integration Event) **선택 기준**은 root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 그대로 따른다. 이 문서는 그 두 패턴을 Go로 어떻게 구현하는지 다룬다.

이 저장소의 `examples/`에는 **두 개의 Bounded Context**가 있다 — Account와 Card. Card는 발급 시 연결 계좌의 활성 여부를 **동기 Adapter(ACL)**로 확인하고, 계좌가 정지/해지되면 **비동기 Integration Event**로 자신의 카드 상태를 따라 바꾼다. 아래 예시는 전부 실제 코드다.

```
[Card BC]                                                  [Account BC]
  internal/application/command/
    account_adapter.go (AccountAdapter 인터페이스 + AccountView)
    issue_card_handler.go (AccountAdapter 사용 — 동기)
    suspend_cards_by_account_handler.go (비동기 반응 유스케이스)
  internal/infrastructure/acl/
    account_adapter.go (구현체) ──────import──────▶  internal/domain/account (account.Query)
  internal/domain/card/ ...

  ▲ 비동기: account.suspended.v1 / account.closed.v1 (Outbox 경유)
```

---

## 패턴 1 — 동기 Adapter/ACL (카드 발급 시 계좌 조회)

카드 발급은 "연결 계좌가 존재하고 활성인가?"를 **응답 시점에 즉시** 판단해야 하므로 동기 호출이다. Card는 Account의 Repository/도메인 모델을 직접 참조하지 않고, 자기 패키지에 정의한 최소 인터페이스(Adapter)를 통해서만 접근한다.

### Step 1 — 호출하는 쪽(Card) Application 레이어에 인터페이스 정의

인터페이스와 반환 타입은 **호출하는 쪽(Card)의 패키지에** 둔다. Account BC의 `Status` enum이나 도메인 모델을 노출하지 않고, Card가 실제로 쓰는 최소 형태(`active bool`)로만 정의한다 — 상류(Account) 모델 변경이 Card로 누수되지 않게 하는 것이 ACL의 목적이다.

```go
// internal/application/command/account_adapter.go
package command

import "context"

// AccountView는 Card BC가 계좌에 대해 실제로 필요로 하는 최소 정보만 담은 값이다.
type AccountView struct {
	AccountID string
	Active    bool
}

// AccountAdapter는 Card BC가 계좌를 동기 조회하기 위한 포트(ACL 인터페이스)다.
// 계좌를 찾지 못하면 (nil, nil)을 반환한다 — 상류의 "계좌 없음" 에러 타입을 Card로
// 누수시키지 않고 nil 신호로 번역하는 것이 구현체의 책임이다.
type AccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*AccountView, error)
}
```

Go의 구조적 타이핑 덕분에 Account BC는 이 인터페이스의 존재조차 몰라도 된다.

### Step 2 — 호출하는 쪽(Card) Infrastructure 레이어에 구현체 작성

구현체(ACL)는 Card의 `internal/infrastructure/acl/`에 둔다. 이 구현체가 Account 도메인을 import하고 `account.Query`를 호출한다 — 의존 방향은 "Card Infrastructure → Account 도메인"이며 Card의 Application/Domain 레이어는 Account를 전혀 모른다.

```go
// internal/infrastructure/acl/account_adapter.go
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

type AccountAdapter struct {
	accounts account.Query // Account BC가 노출한 읽기 인터페이스
}

func NewAccountAdapter(accounts account.Query) *AccountAdapter {
	return &AccountAdapter{accounts: accounts}
}

var _ command.AccountAdapter = (*AccountAdapter)(nil) // 컴파일 타임 검증

func (a *AccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	acc, err := a.accounts.FindByID(ctx, accountID, ownerID)
	if err != nil {
		// 상류의 "계좌 없음"을 Card가 이해하는 nil 신호로 번역한다(오염 방지).
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("account adapter find account: %w", err)
	}
	// Account의 Status enum을 노출하지 않고 active bool로만 번역한다.
	return &command.AccountView{AccountID: acc.AccountID, Active: acc.Status == account.StatusActive}, nil
}
```

> **별도 서비스로 배포된 Account를 호출하는 경우**, `AccountAdapter`는 `account.Query`를 직접 주입받는 대신 HTTP/gRPC 클라이언트를 감싼다(`http.Client` + `context.Context`로 타임아웃 전파). 인터페이스 정의(Step 1)는 그대로 유지되고 구현 내부만 네트워크 호출로 바뀐다 — 이것이 Adapter 패턴의 핵심 이점이다.

### Step 3 — Command Handler에서 Adapter 사용

```go
// internal/application/command/issue_card_handler.go
func (h *IssueCardHandler) Handle(ctx context.Context, cmd IssueCardCommand) (*card.Card, error) {
	view, err := h.accounts.FindAccount(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if view == nil {
		return nil, card.ErrLinkedAccountNotFound
	}
	if !view.Active {
		return nil, card.ErrIssueRequiresActiveAccount
	}
	c := card.IssueCard(cmd.AccountID, cmd.RequesterID, cmd.Brand)
	if err := h.repo.Save(ctx, c); err != nil {
		return nil, err
	}
	return c, nil
}
```

---

## 패턴 2 — 비동기 Integration Event (계좌 상태 변경이 카드로 전파)

계좌가 정지/해지되면 그 계좌의 카드도 따라 정지/해지되어야 한다. 이건 "다른 BC의 상태를 바꾸는" 작업이므로 동기 Adapter가 아니라 **비동기 Integration Event**로 한다(root 원칙). Account가 Card를 import하지 않는 방향으로, Outbox를 매개로 느슨하게 연결한다.

### Step A — Account가 Integration Event를 Outbox에 적재

Account의 Aggregate는 내부 Domain Event(`AccountSuspended`)만 발생시킨다. 그 Domain Event를 처리하는 Application EventHandler(`internal/application/event/`)가 외부 BC용 **버전 명시 Integration Event**(`account.suspended.v1`)로 변환해 Outbox에 새 행으로 적재한다 — 변환 지점은 항상 EventHandler이며, Aggregate는 Integration Event를 직접 만들지 않는다.

```go
// internal/application/integration-event/account_suspended_integration_event.go
package integrationevent

type AccountSuspendedV1 struct {
	AccountID   string    `json:"accountId"`
	SuspendedAt time.Time `json:"suspendedAt"`
}

func (AccountSuspendedV1) EventName() string { return "account.suspended.v1" }
```

```go
// internal/application/event/account_suspended_event_handler.go (발췌)
func (h *AccountSuspendedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountSuspended
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountSuspended: %w", err)
	}
	// 외부 BC용 Integration Event를 Outbox에 적재(별도 row).
	ie := integrationevent.AccountSuspendedV1{AccountID: evt.AccountID, SuspendedAt: evt.SuspendedAt}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish account.suspended.v1: %w", err)
	}
	// 알림은 best-effort — 실패해도 에러를 반환하지 않는다(반환하면 재드레인되어
	// 위 Integration Event가 중복 발행되므로). 발송 실패는 로그로만 남긴다.
	if err := h.notifier.Notify(ctx, evt); err != nil {
		slog.ErrorContext(ctx, "account suspended notification failed", "account_id", evt.AccountID, "error", err)
	}
	return nil
}
```

`publisher`는 `outbox.Publisher`로, 자체 커넥션으로 Outbox 행을 하나 insert한다. `outbox.Relay.ProcessPending`은 한 패스에서 처리한 핸들러가 새 행(Integration Event)을 적재하면 **진전이 있는 한 패스를 반복**하므로, 같은 요청 안에서 이어서 드레인된다.

### Step B — Card가 Integration Event를 구독(반응 유스케이스)

Card 쪽 반응 유스케이스는 순수한 Card 유스케이스다(Account를 import하지 않는다). **멱등**하게 구현한다 — ACTIVE 카드만 골라 정지하므로 같은 이벤트가 재수신돼도(at-least-once) 무해하다.

```go
// internal/application/command/suspend_cards_by_account_handler.go (발췌)
func (h *SuspendCardsByAccountHandler) Handle(ctx context.Context, cmd SuspendCardsByAccountCommand) error {
	cards, _, err := h.repo.FindAll(ctx, card.FindQuery{
		AccountID: cmd.AccountID,
		Status:    []card.Status{card.StatusActive}, // ACTIVE만 → 멱등
		Take:      1000,
	})
	if err != nil {
		return fmt.Errorf("suspend cards by account: %w", err)
	}
	for _, c := range cards {
		if err := c.Suspend(); err != nil {
			return err
		}
		if err := h.repo.Save(ctx, c); err != nil {
			return err
		}
	}
	return nil
}
```

### Step C — `main.go`(합성 루트)가 event_type 문자열로 두 BC를 연결

Account 패키지도 Card 패키지도 서로를 import하지 않는다. **오직 합성 루트(`main.go`)만** 양쪽을 알고, Outbox의 flat `map[string]outbox.Handler`에서 `account.suspended.v1` 문자열을 Card의 반응 유스케이스에 연결한다 — 언마샬 글루만 여기 두고 실제 로직은 Card Application 핸들러에 위임한다.

```go
// cmd/server/main.go (발췌)
outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{
	// ... Account의 내부 Domain Event 핸들러들 ...
	"AccountSuspended": event.NewAccountSuspendedEventHandler(notifier, outboxPublisher).Handle,
	"AccountClosed":    event.NewAccountClosedEventHandler(notifier, outboxPublisher).Handle,
	// Card BC가 Account의 Integration Event에 반응(비동기).
	"account.suspended.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountSuspendedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return suspendCardsHandler.Handle(ctx, command.SuspendCardsByAccountCommand{AccountID: e.AccountID})
	},
	"account.closed.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountClosedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return cancelCardsHandler.Handle(ctx, command.CancelCardsByAccountCommand{AccountID: e.AccountID})
	},
})
```

NestJS는 `EventHandlerRegistry` + DI로 이 구독을 풀지만, Go는 `main.go`에서 map에 항목을 추가하는 것이 전부다 — 별도 registry 없이도 Account↔Card 비의존을 유지한다.

---

## 원칙

- **다른 도메인의 Repository/Service를 Application/Domain 레이어에 직접 주입하지 않는다** — 항상 자신의 패키지에 정의한 Adapter 인터페이스를 거친다(패턴 1).
- **Adapter 인터페이스·반환 타입은 호출하는 쪽이 필요로 하는 최소한의 모양으로** 정의한다 — 호출받는 쪽의 enum/모델/에러를 노출하지 않는다(ACL).
- **쓰기(상태 변경)는 Adapter로 하지 않는다** — 다른 BC의 상태를 바꿔야 하면 Integration Event(비동기)로 전환한다(패턴 2, [domain-events.md](domain-events.md)의 Outbox 패턴).
- **BC 간 비의존은 event_type 문자열 + 합성 루트 배선으로 유지**한다 — Account도 Card도 서로를 import하지 않고, `main.go`만 둘을 안다.
- **비동기 반응 유스케이스는 멱등하게** 만든다 — at-least-once 전달을 전제로 이미 처리된 상태는 건드리지 않는다.
- **`context.Context`를 그대로 전파**한다 — Adapter 호출도 Repository 호출과 동일하게 취소/데드라인을 존중해야 한다.

---

### 관련 문서

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기/비동기 선택 기준 (root, 언어 무관)
- [repository-pattern.md](repository-pattern.md) — 인터페이스/구현체 분리와 컴파일 타임 검증 관용구
- [domain-events.md](domain-events.md) — Outbox 패턴(Writer/Relay/Publisher)과 멱등성
- [module-pattern.md](module-pattern.md) — Adapter/핸들러 배선이 이루어지는 `main.go` 조립 순서
