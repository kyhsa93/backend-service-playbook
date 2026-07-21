package command

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/card"
)

// SendCardUsageStatementCommand는 StatementTaskController(interface/task/)가 Task
// Queue 메시지 페이로드를 역직렬화해 만드는 입력이다. Period는 "2006-01" 형식이며,
// StatementScheduler.EnqueueMonthlyStatement가 enqueue 시점 기준 "지난달"을 그대로
// 페이로드에 실어 보낸다.
type SendCardUsageStatementCommand struct {
	Period string
}

// cardStatementBatchSize — apply_daily_interest_handler.go의 interestBatchSize와
// 동일한 이유로 500을 쓴다.
const cardStatementBatchSize = 500

// SendCardUsageStatementHandler는 Task Queue가 구동하는 시스템 기동 유스케이스다.
// ACTIVE 카드 전체를 순회하며 (1) 연결 계좌 조회 → (2) 기간별 결제 요약 → (3) 이메일
// 발송 → (4) Card.MarkStatementSent로 멱등성 마커 저장을 수행한다. 발송(외부 SES 호출)
// 은 Card 상태에 없는 부작용이므로, 반드시 발송 성공 이후에만 마커를 저장한다 —
// 그래야 발송 실패로 이 Handler가 에러를 반환해 Task가 재시도될 때 실제로 다시
// 발송을 시도한다(at-least-once, scheduling.md).
type SendCardUsageStatementHandler struct {
	repo     card.Repository
	accounts AccountAdapter
	payments PaymentQueryAdapter
	notifier StatementNotifier
}

func NewSendCardUsageStatementHandler(
	repo card.Repository, accounts AccountAdapter, payments PaymentQueryAdapter, notifier StatementNotifier,
) *SendCardUsageStatementHandler {
	return &SendCardUsageStatementHandler{repo: repo, accounts: accounts, payments: payments, notifier: notifier}
}

// Handle은 저장 후 곧바로 반환한다(동기 드레인 금지, domain-events.md) — Card는
// 이 유스케이스에서 Domain Event를 발생시키지 않으므로(MarkStatementSent는 순수
// 상태 마커 갱신) Outbox 경로와는 무관하다.
func (h *SendCardUsageStatementHandler) Handle(ctx context.Context, cmd SendCardUsageStatementCommand) error {
	from, to, err := periodRange(cmd.Period)
	if err != nil {
		return fmt.Errorf("send card usage statement: %w", card.ErrInvalidStatementPeriod)
	}

	for page := 0; ; page++ {
		cards, total, err := h.repo.FindCards(ctx, card.FindQuery{
			Status: []card.Status{card.StatusActive},
			Take:   cardStatementBatchSize,
			Page:   page,
		})
		if err != nil {
			return fmt.Errorf("send card usage statement: find cards: %w", err)
		}

		for _, c := range cards {
			if c.LastStatementSentMonth == cmd.Period {
				continue // 이번 기간은 이미 발송함(Level 1 멱등) — 스킵.
			}

			view, err := h.accounts.FindAccount(ctx, c.AccountID, c.OwnerID)
			if err != nil {
				return fmt.Errorf("send card usage statement: %w", err)
			}
			if view == nil {
				continue // 연결 계좌가 사라짐 — 통지할 대상이 없으므로 조용히 무시한다.
			}

			summary, err := h.payments.SummarizeCardPayments(ctx, c.CardID, from, to)
			if err != nil {
				return fmt.Errorf("send card usage statement: %w", err)
			}

			if err := h.notifier.NotifyCardStatement(
				ctx, c.AccountID, view.Email, c.CardID, cmd.Period, summary.Count, summary.TotalAmount,
			); err != nil {
				return fmt.Errorf("send card usage statement: %w", err)
			}

			if !c.MarkStatementSent(cmd.Period) {
				continue
			}
			if err := h.repo.SaveCard(ctx, c); err != nil {
				return fmt.Errorf("send card usage statement: save card: %w", err)
			}
		}

		if len(cards) == 0 || (page+1)*cardStatementBatchSize >= total {
			break
		}
	}
	return nil
}

// periodRange는 "2006-01" 형식의 period를 [from, to) 반구간으로 변환한다 —
// from은 그 달의 1일 00:00 UTC, to는 다음 달 1일 00:00 UTC다.
func periodRange(period string) (time.Time, time.Time, error) {
	from, err := time.Parse("2006-01", period)
	if err != nil {
		return time.Time{}, time.Time{}, err
	}
	to := from.AddDate(0, 1, 0)
	return from, to, nil
}
