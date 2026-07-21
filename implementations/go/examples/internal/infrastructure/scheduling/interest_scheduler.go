package scheduling

import (
	"context"
	"log/slog"
	"time"
)

// InterestScheduler는 하루에 한 번 "모든 ACTIVE 계좌에 이자를 지급하라" Task를
// 적재한다 — 비즈니스 로직(이자 계산·잔액 반영)은 전혀 하지 않는다. 실제 실행은
// Task Consumer → interface/task.InterestTaskController →
// command.ApplyDailyInterestHandler 경로로 위임한다(scheduling.md, "Scheduler 역할
// 분리").
type InterestScheduler struct {
	taskQueue TaskQueue
}

func NewInterestScheduler(taskQueue TaskQueue) *InterestScheduler {
	return &InterestScheduler{taskQueue: taskQueue}
}

// Run은 ctx가 취소될 때까지 24시간 주기로 enqueue를 반복한다. 실제 신뢰성은 이
// 정확한 tick 시각이 아니라 (1) task_outbox.dedup_id UNIQUE 제약과 (2)
// Account.ApplyInterest의 날짜 기반 멱등성이 담당하므로, 재시작·근사 tick 오차에도
// 안전하다.
func (s *InterestScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.EnqueueDailyInterest(ctx, time.Now().UTC()); err != nil {
				// Cron 예외는 많은 스케줄링 라이브러리가 조용히 삼킨다 — 반드시
				// 명시적으로 로깅한다(scheduling.md). 다음 tick(24시간 뒤)에 재시도되므로
				// 여기서 재throw하지 않는다.
				slog.ErrorContext(ctx, "이자 지급 Task enqueue 실패", "error", err)
			}
		}
	}
}

// EnqueueDailyInterest는 today(UTC) 하루치 이자 지급 Task를 적재한다. Run이 내부적으로
// 호출하지만, 테스트나 운영 도구가 실제 tick을 기다리지 않고 직접 호출할 수 있도록
// export한다(scheduling.md의 예시가 보여주는 것과 동일한 패턴 — Cron 핸들러는
// enqueue만 하는 얇은 함수이므로 그 자체를 직접 검증하면 충분하다).
func (s *InterestScheduler) EnqueueDailyInterest(ctx context.Context, today time.Time) error {
	date := today.Format("2006-01-02")
	dedupID := "account.apply-interest-" + date
	payload := []byte(`{"date":"` + date + `"}`)
	return s.taskQueue.Enqueue(ctx, "account.apply-interest", payload, dedupID)
}
