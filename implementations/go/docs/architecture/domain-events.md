# 도메인 이벤트 (Go) — 수집, Outbox, Relay

원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md)를 따른다: Domain Event는 Aggregate 도메인 메서드 내부에서 수집되고, Repository의 저장 트랜잭션 안에서 **Outbox 테이블에 함께 적재**되어야 하며, 이후 Relay가 Outbox의 미처리 행을 이벤트 핸들러로 전달한다. `examples/`는 이 패턴을 실제로 구현하고 있다 — 이 문서는 (1) Aggregate의 이벤트 수집, (2) Outbox 적재(Repository 저장 트랜잭션 내부), (3) Relay를 통한 동기 드레인까지 실제 코드를 기준으로 설명한다.

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

이 1단계는 root 원칙과 정확히 일치한다. 2, 3단계도 아래에서 보듯 같은 트랜잭션 원자성과 재시도 가능한 드레인을 실제로 갖추고 있다.

---

## 2단계: Outbox 적재 — Repository 저장 트랜잭션 안에서

root가 요구하는 대로, Repository의 저장 메서드 **내부에서, 같은 트랜잭션으로** Aggregate 상태와 도메인 이벤트를 함께 커밋한다.

```sql
-- migrations/0003_add_outbox.sql
CREATE TABLE outbox (
  event_id    VARCHAR(36)  PRIMARY KEY,
  event_type  VARCHAR(50)  NOT NULL,
  payload     JSONB        NOT NULL,
  processed   BOOLEAN      NOT NULL DEFAULT false,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_unprocessed ON outbox (created_at) WHERE processed = false;
```

`internal/infrastructure/outbox/writer.go`의 `Writer.SaveAll`이 이벤트를 JSON으로 직렬화해 outbox에 insert 한다 — 이벤트 타입명은 리플렉션(`reflect.TypeOf(event).Name()`)으로 얻어 `event_type` 컬럼에 그대로 쓴다(예: `account.MoneyDeposited{}` → `"MoneyDeposited"`):

```go
// internal/infrastructure/outbox/writer.go
func (w *Writer) SaveAll(ctx context.Context, tx *sql.Tx, events []account.DomainEvent) error {
	for _, event := range events {
		payload, err := json.Marshal(event)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)`,
			uuid.NewString(), eventTypeName(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}
```

`internal/infrastructure/persistence/account_repository.go`의 `Save`가 계좌/거래 row를 저장한 것과 **같은 `*sql.Tx`** 안에서 `outboxWriter.SaveAll`을 호출한 뒤 커밋한다 — 커밋이 원자적이므로 "계좌는 바뀌었는데 이벤트는 적재되지 않음"이 애초에 발생할 수 없다:

```go
// internal/infrastructure/persistence/account_repository.go
func (r *AccountRepository) Save(ctx context.Context, a *account.Account) error {
	tx, err := r.db.BeginTx(ctx, nil)
	// ...
	defer tx.Rollback()

	// ... accounts row upsert, transactions row insert ...

	if err := r.outboxWriter.SaveAll(ctx, tx, a.DomainEvents()); err != nil {
		return err
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save account: %w", err)
	}
	a.ClearTransactions()
	a.ClearEvents()
	return nil
}
```

---

## 3단계: Relay — 저장 직후 동기 드레인

root는 Outbox를 실제로 소비할 방법을 요구하지만, 별도 폴링 프로세스(고루틴 티커, cron)를 두지 않는다 — 이 저장소는 nestjs 구현체와 동일한 전략을 쓴다: **Command Handler가 `repo.Save()`로 커밋을 마친 직후, 그 자리에서 동기적으로 Outbox 전체를 드레인**한다. 별도 스케줄러가 없으므로 e2e 테스트에서 비동기 완료를 기다리는 타이밍 문제가 없고, 동시에 "커밋되면 반드시 언젠가 처리된다"는 보장도 유지된다 — 실패한 행은 처리됨으로 표시되지 않고 다음 커맨드가 트리거하는 다음 드레인 때 재시도되기 때문이다.

`internal/infrastructure/outbox/relay.go`의 `Relay.ProcessPending`은 **테이블 전체**의 미처리(`processed = false`) 행을 읽어 `event_type`에 매핑된 핸들러를 실행한다 — 자신을 호출한 커맨드가 남긴 이벤트만이 아니라 다른 커맨드가 이전에 실패해 남겨둔 행까지 함께 드레인한다. 개별 행 처리가 실패하면 로그만 남기고 `processed`를 갱신하지 않는다:

```go
// internal/infrastructure/outbox/relay.go
func (r *Relay) ProcessPending(ctx context.Context) error {
	rows, err := r.db.QueryContext(ctx,
		`SELECT event_id, event_type, payload FROM outbox WHERE processed = false ORDER BY created_at`)
	// ... 행을 모두 읽어 pending 슬라이스에 모은다 ...

	for _, row := range pending {
		if err := r.processRow(ctx, row); err != nil {
			log.Printf("outbox: failed to process event_type=%s event_id=%s: %v", row.eventType, row.eventID, err)
		}
	}
	return nil
}

func (r *Relay) processRow(ctx context.Context, row outboxRow) error {
	if handler, ok := r.handlers[row.eventType]; ok {
		if err := handler(ctx, row.payload); err != nil {
			return err
		}
	}
	_, err := r.db.ExecContext(ctx, `UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID)
	return err
}
```

`internal/application/event/`의 이벤트 타입별 핸들러(`AccountCreatedEventHandler`, `MoneyDepositedEventHandler` 등 6개)가 `outbox.Handler` 시그니처(`func(ctx, payload []byte) error`)를 만족한다 — payload JSON을 해당 이벤트 struct로 역직렬화한 뒤, 기존 `notification.Service.Notify`를 그대로 호출한다(이메일 제목/본문/수신자 로직은 그대로 재사용):

```go
// internal/application/event/account_created_event_handler.go
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
```

`notification.Service.Notify`는 실패 시 에러를 반환하고, Relay가 해당 행을 미처리 상태로 남겨 재시도할 수 있게 한다:

```go
// internal/infrastructure/notification/service.go
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) error {
	eventType, content, ok := describe(event)
	if !ok {
		return nil
	}
	if err := s.send(ctx, eventType, content); err != nil {
		return fmt.Errorf("notify %s: %w", eventType, err)
	}
	return nil
}
```

Command Handler는 저장이 끝난 직후 이 드레인을 한 번 호출할 뿐이다 — Outbox 관련 세부사항은 전혀 알지 못한다:

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	// ...
	tx, err := a.Deposit(cmd.Amount)
	// ...
	if err := h.repo.Save(ctx, a); err != nil {       // ← 계좌 상태 + outbox row, 같은 트랜잭션으로 커밋
		return nil, err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {
		return nil, err
	}
	return &tx, nil
}
```

이 경로로 "DB는 커밋됐는데 알림 전송에 실패해서 유실"되는 실패 모드가 사라진다 — Outbox row가 커밋되는 순간 이벤트는 "반드시 언젠가 처리될 것"이 보장되고(at-least-once), 계좌 저장 자체가 롤백되면 이벤트도 애초에 존재하지 않았던 것이 된다.

---

## Integration Event / 멱등성 — 미구현

이 저장소는 Bounded Context가 Account 하나뿐이라 Integration Event(BC 간 공개 계약, `account.deposited.v1` 같은 버저닝)를 보여줄 장면 자체가 없다. 이벤트 핸들러 멱등성 3단계 전략(본질적 멱등 / Ledger / 강한 원자성)도 지금은 이벤트 소비자가 `notification.Service` 하나뿐이라 실질적으로 필요하지 않다 — Outbox + Relay 경로가 메시지 큐를 거치는 별도 프로세스로 확장되면 root 문서의 3단계 전략을 그대로 적용한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의, 과거형 이름 규칙
- [repository-pattern.md](repository-pattern.md) — Repository의 저장 책임과 Outbox 저장 지점
- [persistence.md](persistence.md) — 트랜잭션 전파(Outbox를 같은 트랜잭션에 포함시키기 위한 전제)
- [scheduling.md](scheduling.md) — Task Queue vs Domain Event 구분
