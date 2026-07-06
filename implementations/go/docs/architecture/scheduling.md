# 스케줄링 / 배치 작업 (Go)

원칙은 루트 [scheduling.md](../../../../docs/architecture/scheduling.md)를 따른다: Scheduler는 Infrastructure 레이어에서 **적재(enqueue)만** 하고, 실제 실행은 Task Consumer → Command 경로로 위임한다. Task 적재는 Outbox 경유로 원자성을 보장하고, 핸들러는 멱등해야 한다. **이 저장소의 `examples/`에는 스케줄링/Task Queue 예제가 전혀 없다** — Account 도메인이 주기적 배치가 필요한 유스케이스(만료 계좌 정리 등)를 아직 다루지 않기 때문이다. 이 문서는 Go에서 이 패턴을 어떻게 구현하는지 목표 형태로 제시한다.

---

## Scheduler — `time.Ticker` 기반, Infrastructure 레이어

Go 표준 라이브러리에는 Cron 표현식 파서가 없다(`@nestjs/schedule`의 `@Cron('0 0 * * *')` 상당 기능 부재). 단순 주기 작업은 `time.Ticker`로 충분하고, Cron 표현식이 꼭 필요하면 `github.com/robfig/cron/v3` 같은 최소 의존성 라이브러리를 추가한다.

```go
// internal/infrastructure/scheduling/account_cleanup_scheduler.go — 목표 형태
package scheduling

import (
	"context"
	"log/slog"
	"time"
)

type TaskQueue interface {
	Enqueue(ctx context.Context, taskType string, payload []byte, groupID, dedupID string) error
}

type AccountCleanupScheduler struct {
	taskQueue TaskQueue
}

func NewAccountCleanupScheduler(taskQueue TaskQueue) *AccountCleanupScheduler {
	return &AccountCleanupScheduler{taskQueue: taskQueue}
}

// Run은 ctx가 취소될 때까지 하루 주기로 정리 작업을 적재한다.
// Scheduler는 비즈니스 로직을 직접 실행하지 않는다 — enqueue만 한다.
func (s *AccountCleanupScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.enqueueDailyCleanup(ctx); err != nil {
				// Cron 예외는 라이브러리가 삼키는 경우가 많다 — 반드시 명시적으로 로깅한다.
				slog.ErrorContext(ctx, "cleanup enqueue failed", "error", err)
			}
		}
	}
}

func (s *AccountCleanupScheduler) enqueueDailyCleanup(ctx context.Context) error {
	dedupID := "account.cleanup-expired-" + time.Now().Format("2006-01-02")
	return s.taskQueue.Enqueue(ctx, "account.cleanup-expired", nil, "account.cleanup", dedupID)
}
```

`main.go`에서 다른 인프라와 동일하게 생성자로 조립하고, `context.Context`(graceful-shutdown.md의 시그널 컨텍스트)를 공유해 앱 종료 시 함께 멈춘다:

```go
// cmd/server/main.go — 목표 형태
scheduler := scheduling.NewAccountCleanupScheduler(taskQueue)
go scheduler.Run(ctx) // graceful-shutdown.md의 signal.NotifyContext에서 얻은 ctx
```

Go에서는 이 "백그라운드 주기 작업"이 별도 데코레이터나 모듈 등록 없이 **고루틴 하나**로 표현된다 — `@Injectable() @Cron(...)` 같은 프레임워크 개입이 없다.

---

## Task Outbox 패턴 — DB 변경과 적재의 원자성

root와 동일하게, Command 트랜잭션 안에서 DB 변경과 Task 적재를 함께 커밋해야 dual-write를 피한다. Go 구현은 [domain-events.md](domain-events.md)의 Outbox 목표 패턴과 테이블 구조를 공유한다(`task_outbox` 테이블을 `outbox`와 별도로 두거나, `event_type` 컬럼으로 구분해 하나로 합칠 수도 있다):

```go
// Application Handler — 목표 형태
err := database.WithTx(ctx, db, func(ctx context.Context) error {
	if err := accountRepo.Save(ctx, a); err != nil {
		return err
	}
	return taskOutbox.Enqueue(ctx, "account.archive", []byte(`{"account_id":"`+a.AccountID+`"}`), a.AccountID, "account.archive-"+a.AccountID)
})
```

트랜잭션 컨텍스트가 없는 Scheduler에서 호출할 때도 `TaskQueue.Enqueue`가 내부적으로 `task_outbox` insert 하나만 수행하므로(단일 row insert는 자연스럽게 원자적) 같은 인터페이스를 그대로 재사용할 수 있다.

---

## Task Consumer — Interface 레이어의 입력 어댑터

메시지 큐(SQS 등)에서 메시지를 받아 Command Handler를 호출하는 부분은 HTTP Handler와 대칭적인 역할을 한다. Go에서는 AWS SDK v2의 SQS 클라이언트로 폴링 루프를 직접 작성한다:

```go
// internal/interface/taskqueue/account_task_consumer.go — 목표 형태
type AccountTaskConsumer struct {
	cleanupHandler *command.CleanupExpiredAccountsHandler
}

// HandleCleanup은 로직 없이 Command로 위임한다. 에러는 그대로 반환해
// SQS 재시도/DLQ 메커니즘에 맡긴다 — 여기서 삼키면 실패가 소실된다.
func (c *AccountTaskConsumer) HandleCleanup(ctx context.Context) error {
	return c.cleanupHandler.Handle(ctx, command.CleanupExpiredAccountsCommand{})
}
```

---

## 멱등성

domain-events.md와 동일한 3단계 모델을 적용한다. Account 도메인에서 "만료 계좌 정리"는 이미 본질적으로 멱등하다 — `Account.Close()`가 이미 닫힌 계좌에 대해 `ErrAlreadyClosed`를 반환하고 아무 상태도 바꾸지 않으므로, 같은 Task가 두 번 실행돼도 결과가 같다(Level 1). 부작용이 있는 작업(예: 외부 결제 API 호출)이 추가되면 Level 2(처리 기록 테이블)로 승격한다.

---

## Task vs Domain Event 구분

[domain-events.md](domain-events.md)의 구분을 그대로 따른다 — "사실이 일어났다"(Domain Event, Aggregate가 생성)와 "이 작업을 수행하라"(Task, Scheduler/Application이 생성)는 다른 개념이다. 이 저장소의 이메일 알림(`command.Notifier`)은 Domain Event 소비자에 해당하고, 아직 구현되지 않은 "만료 계좌 정리 배치" 같은 것이 Task Queue의 전형적인 사용처다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event, 멱등성 3단계, Outbox 스키마
- [layer-architecture.md](layer-architecture.md) — Scheduler를 Infrastructure에 두는 근거
- [graceful-shutdown.md](graceful-shutdown.md) — 백그라운드 고루틴의 종료 처리
- [observability.md](observability.md) — Cron 실패의 명시적 로깅
