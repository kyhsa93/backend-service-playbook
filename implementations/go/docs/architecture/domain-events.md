# 도메인 이벤트 (Go) — 수집, Outbox, Poller/Consumer

원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md)가 규정하는 유일한 경로 — **Outbox 적재는 Repository.Save의 트랜잭션 안에서, 큐 발행은 독립적으로 주기 실행되는 `outbox.Poller`가, EventHandler 실행은 SQS를 수신 대기하는 `outbox.Consumer`가** — 를 그대로 구현한다. Command Handler가 저장 직후 같은 프로세스 안에서 동기적으로 드레인하는 방식은 이 저장소에 없다. `internal/infrastructure/outbox/poller.go`/`consumer.go`가 실제 코드이며, 이 문서는 (1) Aggregate의 이벤트 수집, (2) Outbox 적재(Repository 저장 트랜잭션 내부), (3) Poller/Consumer를 통한 비동기 발행·수신까지 실제 코드를 기준으로 설명한다.

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

이 1단계는 root 원칙과 정확히 일치한다. 2, 3, 4단계도 아래에서 보듯 같은 트랜잭션 원자성과 독립적으로 주기 실행되는 발행·수신을 실제로 갖추고 있다.

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
			common.NewID(), eventTypeName(event), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}
	return nil
}
```

`internal/infrastructure/persistence/account_repository.go`의 `SaveAccount`가 계좌/거래 row를 저장한 것과 **같은 `*sql.Tx`** 안에서 `outboxWriter.SaveAll`을 호출한 뒤 커밋한다 — 커밋이 원자적이므로 "계좌는 바뀌었는데 이벤트는 적재되지 않음"이 애초에 발생할 수 없다:

```go
// internal/infrastructure/persistence/account_repository.go
func (r *AccountRepository) SaveAccount(ctx context.Context, a *account.Account) error {
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

Command Handler는 `repo.SaveAccount(ctx, a)`가 끝나면 그대로 반환한다 — outbox나 SQS를 전혀 알지 못한다:

```go
// internal/application/command/deposit_handler.go
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	// ...
	tx, err := a.Deposit(cmd.Amount, "")
	// ...
	if err := h.repo.SaveAccount(ctx, a); err != nil { // ← 계좌 상태 + outbox row, 같은 트랜잭션으로 커밋
		return nil, err
	}
	return &tx, nil // 여기서 끝난다 — Poller/Consumer가 독립적으로 처리한다.
}
```

**"같은 프로세스 안에서 저장 직후 동기적으로 드레인"하는 방식은 쓰지 않는다.** `implementations/go/harness/outbox_drain_order.go`가 Command Handler(`*_handler.go`, `*_event_handler.go` 제외)가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 참조하거나 `ProcessPending()`/`Poll()`/`drainOnce()`류를 호출하면 FAIL로 잡아낸다(`outbox-drain-order` 규칙).

---

## 3단계: Poller — Outbox → SQS 전송

`internal/infrastructure/outbox/poller.go`의 `Poller.Run(ctx)`가 `time.NewTicker(1 * time.Second)`로 1초마다 단독 실행된다 — Command Handler는 이 struct를 전혀 참조하지 않는다. `cmd/server/main.go`가 `go outboxPoller.Run(ctx)`로 시작하는 백그라운드 goroutine이며, `signal.NotifyContext`가 취소하는 것과 같은 `ctx`를 공유해 graceful shutdown 때 함께 멈춘다.

```go
// internal/infrastructure/outbox/poller.go
func (p *Poller) Run(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := p.drainOnce(ctx); err != nil {
				slog.ErrorContext(ctx, "outbox 폴링 실패", "error", err)
			}
		}
	}
}

func (p *Poller) drainOnce(ctx context.Context) error {
	rows, err := p.fetchPending(ctx) // SELECT ... WHERE processed = false ORDER BY created_at LIMIT 100
	if err != nil {
		return err
	}
	for _, row := range rows {
		if err := p.publish(ctx, row); err != nil {
			slog.ErrorContext(ctx, "SQS 발행 실패", "event_type", row.eventType, "event_id", row.eventID, "error", err)
		}
	}
	return nil
}

func (p *Poller) publish(ctx context.Context, row outboxRow) error {
	if _, err := p.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(row.payload)),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"eventType": {DataType: aws.String("String"), StringValue: aws.String(row.eventType)},
		},
	}); err != nil {
		return fmt.Errorf("send sqs message: %w", err)
	}
	_, err := p.db.ExecContext(ctx, `UPDATE outbox SET processed = true WHERE event_id = $1`, row.eventID)
	return err
}
```

`processed=true`는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는 뜻이다 — 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility timeout + DLQ가 담당한다. 발행에 실패한 행은 `processed=false`로 남아 다음 tick에서 재시도된다.

---

## 4단계: Consumer — SQS → Handler 실행

`internal/infrastructure/outbox/consumer.go`의 `Consumer.Run(ctx)`는 `ReceiveMessage`의 `WaitTimeSeconds: 5`로 long polling하다가 메시지를 받으면 `eventType`(MessageAttributes)으로 `handlers` map에서 핸들러를 찾아 호출한다. `main.go`가 `go outboxConsumer.Run(ctx)`로 단 한 번 시작하는 백그라운드 goroutine이다 — 요청마다 새로 만들어지지 않는다.

```go
// internal/infrastructure/outbox/consumer.go
func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		result, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:              aws.String(c.queueURL),
			MaxNumberOfMessages:   10,
			MessageAttributeNames: []string{"eventType"},
			WaitTimeSeconds:       5,
		})
		if err != nil {
			if ctx.Err() != nil {
				return // graceful shutdown 중 ReceiveMessage가 취소된 경우
			}
			slog.ErrorContext(ctx, "SQS 수신 실패", "error", err)
			continue
		}
		for _, message := range result.Messages {
			c.handleMessage(ctx, message)
		}
	}
}

func (c *Consumer) handleMessage(ctx context.Context, message types.Message) {
	eventType := ""
	if attr, ok := message.MessageAttributes["eventType"]; ok && attr.StringValue != nil {
		eventType = *attr.StringValue
	}
	handler, ok := c.handlers[eventType]
	if !ok {
		slog.ErrorContext(ctx, "등록된 핸들러를 찾지 못함 — 재시도로 남겨둠", "event_type", eventType)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}
	if err := handler(ctx, []byte(aws.ToString(message.Body))); err != nil {
		slog.ErrorContext(ctx, "이벤트 처리 실패", "event_type", eventType, "error", err)
		return // 삭제하지 않음 — visibility timeout 이후 재수신되어 재시도된다.
	}
	if _, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl: aws.String(c.queueURL), ReceiptHandle: message.ReceiptHandle,
	}); err != nil {
		slog.ErrorContext(ctx, "메시지 삭제 실패", "event_type", eventType, "error", err)
	}
}
```

`handlers`(`map[string]outbox.Handler`, `Handler = func(ctx, payload []byte) error`)는 `cmd/server/main.go`가 단 한 번 조립하는 **단일 공유 map**이다 — `internal/application/event/`의 도메인 이벤트 핸들러 6개와, Card/Payment BC가 반응하는 Integration Event 핸들러(글루 클로저)가 모두 이 map 하나에 등록된다:

```go
// internal/application/event/account_created_event_handler.go — outbox.Handler 시그니처를 만족
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
```

```go
// cmd/server/main.go (발췌)
outboxHandlers := map[string]outbox.Handler{
	"AccountCreated": event.NewAccountCreatedEventHandler(notifier).Handle,
	// ... 나머지 Domain Event Handler ...
	"account.suspended.v1": func(ctx context.Context, payload []byte) error {
		var e integrationevent.AccountSuspendedV1
		if err := json.Unmarshal(payload, &e); err != nil {
			return err
		}
		return suspendCardsHandler.Handle(ctx, command.SuspendCardsByAccountCommand{AccountID: e.AccountID})
	},
	// ... 나머지 Integration Event 반응 ...
}
outboxPoller := outbox.NewPoller(db, sqsClient, sqsConfig.QueueURL)
outboxConsumer := outbox.NewConsumer(sqsClient, sqsConfig.QueueURL, outboxHandlers)

go outboxPoller.Run(ctx)
go outboxConsumer.Run(ctx)
```

`notification.Service.Notify`는 실패 시 에러를 반환하고, Consumer가 해당 메시지를 삭제하지 않아 SQS가 재전달하게 한다:

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

이 경로로 "DB는 커밋됐는데 알림 전송에 실패해서 유실"되는 실패 모드가 사라진다 — Outbox row가 커밋되는 순간 이벤트는 "반드시 언젠가 큐로 나가고 처리될 것"이 보장되고(at-least-once, outbox의 재시도는 SQS의 visibility timeout + DLQ로 넘어간다), 계좌 저장 자체가 롤백되면 이벤트도 애초에 존재하지 않았던 것이 된다.

### SQS 클라이언트 — 실제 코드

`internal/infrastructure/outbox/sqs_client.go`의 `NewSQSClient()`는 `notification.NewSESClient()`와 정확히 같은 구성(`AWS_ENDPOINT_URL` 분기, 정적 test 자격증명 기본값)을 따른다 — Poller/Consumer가 이 하나의 클라이언트를 공유한다. 큐 URL은 `internal/config/sqs.go`의 `LoadSQSConfig()`가 `SQS_DOMAIN_EVENT_QUEUE_URL` 환경 변수에서 읽는다(fail-fast, `DatabaseConfig`와 동일한 패턴).

---

## Integration Event / 멱등성

Account와 Card 두 Bounded Context가 있어 Integration Event(BC 간 공개 계약, `account.suspended.v1`/`account.closed.v1` 버저닝)가 실제로 존재한다 — `internal/application/integration-event/`의 `account_suspended_integration_event.go`/`account_closed_integration_event.go` 참고. Card 쪽 반응 유스케이스(`suspend_cards_by_account_handler.go`/`cancel_cards_by_account_handler.go`)는 ACTIVE(또는 ACTIVE·SUSPENDED)인 카드만 골라 처리하는 방식으로 멱등하게 구현되어 있다 — SQS의 at-least-once 전달을 전제로 같은 이벤트가 재수신돼도 무해하다. 상세는 [cross-domain.md](cross-domain.md) 참고. 이벤트 핸들러 멱등성 3단계 전략(본질적 멱등 / Ledger / 강한 원자성) 중, Card BC 반응은 본질적 멱등(상태 전이 기반)을, Payment BC 반응(`WithdrawByPaymentHandler`/`DepositByPaymentHandler`)은 `referenceId` 기준 Level 2 Ledger(`HasTransactionWithReference`)를 실제로 쓴다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의, 과거형 이름 규칙
- [repository-pattern.md](repository-pattern.md) — Repository의 저장 책임과 Outbox 저장 지점
- [persistence.md](persistence.md) — 트랜잭션 전파(Outbox를 같은 트랜잭션에 포함시키기 위한 전제)
- [scheduling.md](scheduling.md) — Task Queue vs Domain Event 구분, DLQ/멱등성 컨벤션
- [local-dev.md](local-dev.md) — LocalStack SQS 로컬 실행
