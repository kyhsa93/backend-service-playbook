# 스케줄링 / 배치 작업 (Go)

원칙은 루트 [scheduling.md](../../../../docs/architecture/scheduling.md)를 따른다: Scheduler는 Infrastructure 레이어에서 **적재(enqueue)만** 하고, 실제 실행은 Task Consumer → Task Controller(Interface 레이어) → Command Service 경로로 위임한다. Task 적재는 `task_outbox` 테이블을 경유해 원자성을 보장하고, Command Handler는 멱등하게 구현한다.

`examples/`에는 이 패턴을 실제로 쓰는 두 배치가 있다 — **일일 이자 지급**(모든 ACTIVE 계좌에 매일 이자를 지급)과 **월간 카드 사용내역 발송**(모든 ACTIVE 카드에 지난달 결제 요약을 이메일로 보낸다). 아래는 그 실제 구현을 설명한다.

---

## 전체 흐름

```
[InterestScheduler/StatementScheduler]   internal/infrastructure/scheduling/
  --(enqueue)-->
[task_outbox 테이블]                     같은 프로세스, 트랜잭션 없는 단일 row INSERT
  --(taskqueue.Poller, 1초 tick)-->
[task-queue SQS 큐]                      internal/infrastructure/task-queue/
  --(taskqueue.Consumer, long polling)-->
[InterestTaskController/StatementTaskController]  internal/interface/task/
  --(호출)-->
[ApplyDailyInterestHandler/SendCardUsageStatementHandler]  internal/application/command/
```

`domain-events`(Outbox → SQS `domain-events` 큐)와 완전히 분리된 별도 테이블(`task_outbox`)·별도 SQS 큐(`task-queue`)를 쓴다 — "사실이 일어났다"(Domain Event)와 "명령: X를 수행하라"(Task)는 다른 의미 단위이기 때문이다([domain-events.md](domain-events.md)의 "Task Queue vs Domain Event" 구분). `internal/infrastructure/outbox/`와 `internal/infrastructure/task-queue/`는 구조가 거울상이지만(Writer/Poller/Consumer) 완전히 독립된 두 벌이다.

---

## Scheduler — `time.Ticker` 기반, Infrastructure 레이어

Go 표준 라이브러리에는 Cron 표현식 파서가 없다. 두 Scheduler 모두 `time.Ticker`로 24시간 주기 tick만 쓴다.

```go
// internal/infrastructure/scheduling/interest_scheduler.go
type InterestScheduler struct {
	taskQueue TaskQueue
}

func (s *InterestScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.EnqueueDailyInterest(ctx, time.Now().UTC()); err != nil {
				slog.ErrorContext(ctx, "이자 지급 Task enqueue 실패", "error", err)
			}
		}
	}
}

// EnqueueDailyInterest는 테스트/운영 도구가 실제 tick을 기다리지 않고 직접 호출할 수
// 있도록 export되어 있다.
func (s *InterestScheduler) EnqueueDailyInterest(ctx context.Context, today time.Time) error {
	date := today.Format("2006-01-02")
	dedupID := "account.apply-interest-" + date
	payload := []byte(`{"date":"` + date + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.apply-interest", payload, dedupID)
}
```

`StatementScheduler`(`internal/infrastructure/scheduling/statement_scheduler.go`)는 "달(month)" 주기를 고정 길이 `time.Ticker`로 표현할 수 없으므로, 24시간 tick마다 "오늘이 1일인가"를 게이트로 근사한다 — 정확한 실행 시각의 책임은 여기가 아니라 `task_outbox.dedup_id` UNIQUE 제약과 `Card.MarkStatementSent`의 멱등성이 진다.

`time.Ticker`/`time.NewTicker` 참조가 `internal/infrastructure/`에만 등장하는지는 `implementations/go/harness/scheduler_in_infrastructure_only.go`가 자동으로 검사한다.

`main.go`에서 다른 인프라와 동일하게 생성자로 조립하고, graceful-shutdown의 시그널 컨텍스트를 공유해 앱 종료 시 함께 멈춘다:

```go
// cmd/server/main.go
go interestScheduler.Run(ctx)
go statementScheduler.Run(ctx)
```

---

## Task Outbox — `task_outbox` 테이블, DB 변경과 적재의 원자성

`task_outbox`(`migrations/0007_add_scheduling.sql`)는 `outbox`와 스키마 구조는 비슷하지만(`task_id`/`task_type`/`payload`/`processed`/`created_at`) 완전히 별도 테이블이다. `dedup_id` 컬럼에 UNIQUE 제약을 걸어 **"같은 날짜/기간의 Cron enqueue가 중복 적재되지 않는다"를 DB가 직접 보장**한다:

```go
// internal/infrastructure/task-queue/writer.go
func (w *Writer) Enqueue(ctx context.Context, taskType string, payload []byte, dedupID string) error {
	_, err := w.db.ExecContext(ctx,
		`INSERT INTO task_outbox (task_id, task_type, payload, dedup_id) VALUES ($1, $2, $3, $4)
		 ON CONFLICT (dedup_id) DO NOTHING`,
		common.NewID(), taskType, payload, dedupID)
	...
}
```

Scheduler(Cron)에는 트랜잭션 문맥이 없으므로, 이 단일 row INSERT 자체가 원자성의 전부다(root scheduling.md가 설명하는 것과 동일한 이유). 여러 인스턴스가 동시에 같은 날짜의 tick을 실행해도 `ON CONFLICT DO NOTHING`으로 1건만 남는다.

---

## Task Consumer — Infrastructure 레이어의 백그라운드 루프

`internal/infrastructure/task-queue/poller.go`(`task_outbox` → SQS 발행)와 `consumer.go`(SQS → Task Controller 호출)는 `internal/infrastructure/outbox/`의 Poller/Consumer와 완전히 같은 모양이다 — 메시지 속성 이름만 `eventType` 대신 `taskType`을 쓴다. `main.go`에서 독립 goroutine으로 띄운다(HTTP 요청과 무관하게 항상 주기 실행).

---

## Task Controller — Interface 레이어

`internal/interface/task/interest_task_controller.go`, `statement_task_controller.go`는 HTTP Handler와 대칭적인 입력 어댑터다 — 로직 없이 페이로드를 역직렬화해 Command Service를 호출하고, 에러를 그대로 던진다(HTTP Handler처럼 catch해서 상태 코드로 변환하지 않는다):

```go
// internal/interface/task/interest_task_controller.go
func (c *InterestTaskController) HandleApplyInterest(ctx context.Context, payload []byte) error {
	var p interestTaskPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return fmt.Errorf("unmarshal account.apply-interest task payload: %w", err)
	}
	return c.handler.Handle(ctx, command.ApplyDailyInterestCommand{Date: p.Date}) // 예외는 그대로 위로
}
```

`taskqueue.Consumer`가 이 에러를 받아 메시지를 삭제하지 않고 남겨두므로, SQS의 visibility timeout 이후 자동 재시도되고 `maxReceiveCount`를 넘기면 DLQ로 격리된다.

`main.go`가 taskType 문자열 → Task Controller 메서드로 매핑하는 단일 공유 `map[string]taskqueue.Handler`를 조립한다(`outbox.Handler` map과 동일한 관용구):

```go
taskHandlers := map[string]taskqueue.Handler{
	"account.apply-interest":    interestTaskController.HandleApplyInterest,
	"card.send-usage-statement": statementTaskController.HandleSendStatement,
}
```

---

## 멱등성 — 두 배치 모두 Level 1(본질적 멱등)

domain-events.md의 3단계 모델을 그대로 적용한다. 두 배치 모두 별도 Ledger 테이블 없이, Aggregate 자신의 상태 필드만으로 "이번 주기에 이미 처리했는가"를 판단한다.

**일일 이자 지급 — `Account.LastInterestPaidAt`:**

```go
// internal/domain/account/account.go
func (a *Account) ApplyInterest(rate float64, today time.Time) (Transaction, bool, error) {
	if a.Status != StatusActive {
		return Transaction{}, false, ErrInterestRequiresActiveAccount
	}
	if isSameDate(a.LastInterestPaidAt, today) {
		return Transaction{}, false, nil // 오늘 이미 지급함 — 조용히 스킵
	}
	interestAmount := int64(float64(a.Balance.Amount) * rate) // floor(balance * rate)
	if interestAmount <= 0 {
		return Transaction{}, false, nil // 이자가 0이면 상태를 바꾸지 않는다 — 재계산해도 항상 0
	}
	...
	a.LastInterestPaidAt = today
	a.events = append(a.events, InterestPaid{...})
	return tx, true, nil
}
```

이자가 지급되면 `Deposit()`과 동일하게 `Transaction`(`TransactionTypeInterest = "INTEREST"`)을 남기고 `InterestPaid` Domain Event를 발생시킨다 — 이 이벤트는 기존 `outbox`(Domain Event 경로)를 그대로 타고 `application/event/interest_paid_event_handler.go`가 소비해 이메일로 알린다. **Task Queue(이 배치 자체)와 Domain Event(그 결과로 생긴 이자 지급 사실)가 한 유스케이스 안에서 함께 쓰이는 예다.**

**월간 카드 사용내역 발송 — `Card.LastStatementSentMonth`:**

```go
// internal/domain/card/card.go
func (c *Card) MarkStatementSent(period string) bool {
	if c.LastStatementSentMonth == period {
		return false // 이미 이번 기간을 보냈음 — 멱등 no-op
	}
	c.LastStatementSentMonth = period
	return true
}
```

이메일 발송(SES 호출)은 Card가 모르는 외부 부작용이므로, `SendCardUsageStatementHandler`는 **발송에 성공한 뒤에만** `MarkStatementSent`를 호출한다 — 그래야 발송 실패로 Task가 재시도될 때 실제로 다시 발송을 시도한다.

---

## Cron 다중 인스턴스 안전성 — 날짜 기반 `dedup_id`

```go
// 일일 배치
dedupID := "account.apply-interest-" + today.Format("2006-01-02")

// 월간 배치 — "2006-01" 형식의 period
dedupID := "card.send-usage-statement-" + period
```

여러 인스턴스가 같은 날 동시에 tick을 실행해도 `task_outbox.dedup_id` UNIQUE 제약으로 1건만 적재된다.

---

## Cross-BC 조회 — `PaymentQueryAdapter`

카드 사용내역 요약(건수·합계)은 Payment BC의 데이터가 필요하다. Card의 Application 레이어는 `payment` 도메인 패키지를 직접 import하지 않고(no-cross-bc-repository-in-application 규칙), Account/Payment가 서로를 참조할 때와 동일한 ACL 패턴을 쓴다:

```go
// internal/application/command/payment_query_adapter.go — Card BC가 정의하는 포트
type PaymentQueryAdapter interface {
	SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (CardPaymentSummary, error)
}

// internal/infrastructure/acl/card_payment_adapter.go — 구현체(payment.Query를 호출)
type CardPaymentAdapter struct{ payments payment.Query }
```

`payment.FindQuery`에 `CreatedFrom`/`CreatedTo` 날짜 범위 필터를 추가해([repository.go](../../../../implementations/go/examples/internal/domain/payment/repository.go)) 이 조회를 지원한다. COMPLETED 결제만 집계한다 — PENDING/FAILED/CANCELLED는 실제 청구되지 않았기 때문이다.

---

## 알림 — 기존 `notification.Service` 재사용

카드 사용내역 이메일은 계좌 Domain Event 알림과 동일한 `internal/infrastructure/notification/service.go`가 보낸다(SES 발송 + `sent_emails` 감사 로그). `Notify(event account.DomainEvent)`가 하는 실제 발송·기록 로직(`send()`)은 애초에 `account` 타입에 의존하지 않는 순수 함수라, `NotifyCardStatement(...)`라는 새 메서드가 같은 `send()`를 재사용한다 — Card BC 전용 알림 메커니즘을 새로 만들지 않는다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event, 멱등성 3단계, Outbox 스키마
- [layer-architecture.md](layer-architecture.md) — Scheduler를 Infrastructure에 두는 근거
- [graceful-shutdown.md](graceful-shutdown.md) — 백그라운드 고루틴의 종료 처리
- [observability.md](observability.md) — Cron 실패의 명시적 로깅
