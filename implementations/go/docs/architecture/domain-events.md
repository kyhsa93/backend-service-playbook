# 도메인 이벤트 (Go) — 수집, Outbox, 알려진 격차

원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md)를 따른다: Domain Event는 Aggregate 도메인 메서드 내부에서 수집되고, Repository의 저장 트랜잭션 안에서 **Outbox 테이블에 함께 적재**되어야 하며, 별도 Relay 프로세스가 Outbox를 폴링해 메시지 큐로 전송한다. 이 문서는 (1) 그 올바른 패턴을 Go로 어떻게 구현하는지, (2) **이 저장소의 `examples/`가 실제로는 다른, 더 단순한 방식을 쓰고 있으며 이것이 root가 명시적으로 경계하는 dual-write 문제라는 점**을 정확히 짚는다.

---

## 1단계: Aggregate에서 이벤트 수집 — 올바르게 구현되어 있음

`internal/domain/account/events.go`가 `DomainEvent` 인터페이스와 이벤트 타입들을 정의하고, `account.go`의 도메인 메서드가 상태 변경 시 이벤트를 슬라이스에 쌓는다:

```go
// internal/domain/account/events.go
type DomainEvent interface {
	isAccountDomainEvent()
}

type MoneyDeposited struct {
	AccountID     string
	Email         string
	TransactionID string
	Amount        Money
	BalanceAfter  Money
	CreatedAt     time.Time
}

func (MoneyDeposited) isAccountDomainEvent() {}
```

```go
// internal/domain/account/account.go
func (a *Account) Deposit(amount int64) (Transaction, error) {
	// ... 불변식 검증, 상태 변경 ...
	a.events = append(a.events, MoneyDeposited{
		AccountID: a.AccountID, Email: a.Email, TransactionID: tx.TransactionID,
		Amount: money, BalanceAfter: a.Balance, CreatedAt: tx.CreatedAt,
	})
	return tx, nil
}

func (a *Account) DomainEvents() []DomainEvent { return a.events }
func (a *Account) ClearEvents()                { a.events = nil }
```

`isAccountDomainEvent()`는 Go에 sealed interface(합집합 타입)가 없어서 쓰는 관용구다 — 이 빈 메서드를 구현한 타입만 `DomainEvent`를 만족하므로, 패키지 밖의 임의 타입이 실수로 `DomainEvent`를 만족하는 것을 막는다(TypeScript의 discriminated union이 하는 역할을 Go의 unexported 메서드로 흉내).

이 1단계는 root 원칙과 정확히 일치한다. 문제는 2단계부터다.

---

## 2단계: root가 요구하는 Outbox 패턴 — 목표 구현

root는 Repository의 저장 메서드 **내부에서, 같은 트랜잭션으로** Aggregate 상태와 도메인 이벤트를 함께 커밋하라고 요구한다. Go로 옮기면 이런 모습이어야 한다:

```sql
-- migrations/000X_add_outbox.sql — 목표 스키마 (아직 이 저장소엔 없음)
CREATE TABLE outbox (
  event_id    VARCHAR(36)  PRIMARY KEY,
  event_type  VARCHAR(50)  NOT NULL,
  payload     JSONB        NOT NULL,
  processed   BOOLEAN      NOT NULL DEFAULT false,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_unprocessed ON outbox (created_at) WHERE processed = false;
```

```go
// internal/infrastructure/persistence/account_repository.go — 목표 형태
func (r *AccountRepository) Save(ctx context.Context, a *account.Account) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `INSERT INTO accounts (...) VALUES (...) ON CONFLICT ...`, ...); err != nil {
		return fmt.Errorf("save account: %w", err)
	}
	// ... transactions 테이블 insert (기존과 동일) ...

	for _, event := range a.DomainEvents() {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			common.NewID(), eventType(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearEvents()
	a.ClearTransactions()
	return nil
}
```

그리고 별도 **OutboxRelay** 프로세스(고루틴 또는 별도 바이너리)가 짧은 주기로 폴링해 미처리 이벤트를 메시지 큐(SQS 등)로 보내고 `processed = true`로 마크한다:

```go
// internal/infrastructure/outbox/relay.go — 목표 형태
func (r *Relay) Run(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := r.relayOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "outbox relay failed", "error", err)
			}
		}
	}
}
```

이 경로가 확보되면 "DB는 커밋됐는데 알림 전송에 실패해서 유실"되거나 "알림은 나갔는데 DB가 롤백돼 사실과 다른 알림이 발송"되는 두 가지 실패 모드가 모두 사라진다 — Outbox row가 커밋되는 순간 이벤트는 "반드시 언젠가 전송될 것"이 보장되고(at-least-once), 롤백되면 이벤트 자체가 존재하지 않았던 것이 된다.

---

## 3단계: 현재 `examples/`의 실제 코드 — dual-write 안티패턴

`internal/application/command/deposit_handler.go`를 비롯한 모든 Command Handler는 Outbox를 전혀 거치지 않고, **저장 커밋이 끝난 뒤 별도로 `Notifier.Notify()`를 동기 호출**한다:

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	// ...
	tx, err := a.Deposit(cmd.Amount)
	// ...
	if err := h.repo.Save(ctx, a); err != nil {   // ← 트랜잭션 1: 계좌 저장, 여기서 커밋됨
		return nil, err
	}
	notify(ctx, h.notifier, a)                     // ← 별개의 후속 호출: 여기서 실패해도 위 커밋은 되돌릴 수 없음
	return &tx, nil
}
```

```go
// internal/application/command/notifier.go
func notify(ctx context.Context, notifier Notifier, a *account.Account) {
	for _, event := range a.DomainEvents() {
		notifier.Notify(ctx, event)   // Notifier.Notify는 에러를 반환하지 않는다 — 실패해도 알 방법이 없음
	}
	a.ClearEvents()
}
```

```go
// internal/infrastructure/notification/service.go — 주석이 이 설계를 스스로 인정하고 있다
// Notify는 도메인 이벤트에 대응하는 알림 이메일을 발송하고 발송 내역을 저장한다.
// 이메일 발송 또는 저장에 실패해도 에러를 반환하지 않고 로그만 남긴다 — 알림은
// 부가 기능이므로 계좌 커맨드의 성공 여부에 영향을 주면 안 된다.
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) {
	// ...
	if err := s.send(ctx, eventType, content); err != nil {
		log.Printf("notification: failed to send email for event=%s recipient=%s: %v", eventType, content.recipient, err)
	}
}
```

이것이 정확히 root가 "dual-write 문제"로 경계하는 패턴이다: DB 쓰기와 알림 발송이 **원자적으로 묶여 있지 않다**. `repo.Save()`가 커밋된 직후 프로세스가 죽거나 SES 호출이 타임아웃되면, 계좌 상태는 바뀌었는데 알림은 영영 나가지 않는다 — 그리고 실패가 `log.Printf`로만 남고 재시도 메커니즘이 없으므로 그 유실은 관찰조차 되지 않는다. `Notify`의 시그니처 자체가 `error`를 반환하지 않아, 호출부인 Command Handler는 애초에 실패 여부를 알 수도 없다.

**이것은 이번 문서화 패스에서 고치지 않는 알려진 편차다.** 트래픽이 적고 이메일 알림이 "있으면 좋은" 부가 기능인 현재 규모에서는 실용적인 절충이지만, 알림이 비즈니스적으로 중요해지거나(예: 법적 통지) 처리량이 늘어나면 위 2단계의 Outbox 경로로 옮겨야 한다.

---

## Integration Event / 멱등성 — 미구현

이 저장소는 Bounded Context가 Account 하나뿐이라 Integration Event(BC 간 공개 계약, `account.deposited.v1` 같은 버저닝)를 보여줄 장면 자체가 없다. 이벤트 핸들러 멱등성 3단계 전략(본질적 멱등 / Ledger / 강한 원자성)도 지금은 이벤트 소비자가 `notification.Service` 하나뿐이라 실질적으로 필요하지 않았다 — Outbox + Relay + Consumer 경로가 생기면 root 문서의 3단계 전략을 그대로 적용한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의, 과거형 이름 규칙
- [repository-pattern.md](repository-pattern.md) — Repository의 저장 책임과 Outbox 저장 지점
- [persistence.md](persistence.md) — 트랜잭션 전파(Outbox를 같은 트랜잭션에 포함시키기 위한 전제)
- [scheduling.md](scheduling.md) — Task Queue vs Domain Event 구분
