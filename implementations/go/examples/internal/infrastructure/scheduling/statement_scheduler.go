package scheduling

import (
	"context"
	"log/slog"
	"time"
)

// StatementScheduler는 매달 한 번 "모든 ACTIVE 카드의 지난달 사용내역을 발송하라"
// Task를 적재한다. Go 표준 라이브러리에는 Cron 표현식 파서가 없고(scheduling.md),
// "달(month)" 주기는 고정 길이 time.Ticker로 표현할 수 없으므로 하루 주기 tick +
// "오늘이 1일인가" 게이트로 근사한다 — 정확한 실행 시각의 책임은 여기가 아니라
// task_outbox.dedup_id UNIQUE 제약(같은 period가 중복 적재되지 않음)과
// Card.MarkStatementSent(같은 period는 다시 보내지 않음)가 진다.
type StatementScheduler struct {
	taskQueue TaskQueue
}

func NewStatementScheduler(taskQueue TaskQueue) *StatementScheduler {
	return &StatementScheduler{taskQueue: taskQueue}
}

func (s *StatementScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := time.Now().UTC()
			if now.Day() != 1 {
				continue // 매달 1일에만 enqueue한다 — 그 외에는 값싼 no-op.
			}
			if err := s.EnqueueMonthlyStatement(ctx, now); err != nil {
				slog.ErrorContext(ctx, "카드 사용내역 Task enqueue 실패", "error", err)
			}
		}
	}
}

// EnqueueMonthlyStatement는 now 기준 바로 이전 달("2006-01" 형식)의 카드 사용내역 발송
// Task를 적재한다 — 예를 들어 8월 1일에 호출되면 "2026-07"을 대상 period로 삼는다.
// Run이 내부적으로 호출하지만, 테스트/운영 도구가 실제 tick(최대 24시간)을 기다리지
// 않고 직접 호출할 수 있도록 export한다.
func (s *StatementScheduler) EnqueueMonthlyStatement(ctx context.Context, now time.Time) error {
	period := previousMonth(now)
	dedupID := "card.send-usage-statement-" + period
	payload := []byte(`{"period":"` + period + `"}`)
	return s.taskQueue.Enqueue(ctx, "card.send-usage-statement", payload, dedupID)
}

// previousMonth는 now가 속한 달의 바로 이전 달을 "2006-01" 형식으로 반환한다.
func previousMonth(now time.Time) string {
	firstOfThisMonth := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	lastMonth := firstOfThisMonth.AddDate(0, -1, 0)
	return lastMonth.Format("2006-01")
}
