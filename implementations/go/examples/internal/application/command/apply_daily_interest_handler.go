package command

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/account"
)

// ApplyDailyInterestCommand는 InterestTaskController(interface/task/)가 Task Queue
// 메시지 페이로드를 역직렬화해 만드는 입력이다. Date는 "2006-01-02" 형식이며,
// InterestScheduler.EnqueueDailyInterest가 enqueue 시점의 날짜를 그대로 페이로드에
// 실어 보낸다 — Consumer가 이 메시지를 나중에(자정을 넘겨) 처리하더라도 "어느 날짜의
// 이자인가"가 흔들리지 않는다.
type ApplyDailyInterestCommand struct {
	Date string
}

// ApplyDailyInterestHandler는 Task Queue가 구동하는 시스템 기동 유스케이스다 — 사용자
// 커맨드가 아니므로 RequesterID 같은 인가 정보를 받지 않는다. ACTIVE 계좌 전체를
// 페이지 단위로 순회하며 Account.ApplyInterest(Level 1 멱등)를 호출한다.
type ApplyDailyInterestHandler struct {
	repo account.Repository
	rate float64
}

func NewApplyDailyInterestHandler(repo account.Repository, rate float64) *ApplyDailyInterestHandler {
	return &ApplyDailyInterestHandler{repo: repo, rate: rate}
}

// interestBatchSize는 한 페이지에서 조회할 계좌 수다. suspend_cards_by_account_handler.go
// 등 기존 배치성 Handler들의 Take:1000 관용구보다 작게 잡아, 계좌 수가 많은 환경에서도
// 한 페이지 조회/처리 시간이 과도해지지 않게 한다.
const interestBatchSize = 500

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기 실행되는
// outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지, domain-events.md).
// Account.ApplyInterest가 발생시키는 InterestPaid Domain Event도 이 경로를 그대로
// 탄다 — Task Queue(이 배치 자체)와 Domain Event(그 결과로 생긴 이자 지급 사실)는
// 서로 다른 두 메커니즘이 한 유스케이스 안에서 함께 쓰이는 예다.
func (h *ApplyDailyInterestHandler) Handle(ctx context.Context, cmd ApplyDailyInterestCommand) error {
	today, err := time.Parse("2006-01-02", cmd.Date)
	if err != nil {
		return fmt.Errorf("apply daily interest: %w", account.ErrInvalidInterestDate)
	}

	for page := 0; ; page++ {
		accounts, total, err := h.repo.FindAccounts(ctx, account.FindQuery{
			Status: []account.Status{account.StatusActive},
			Take:   interestBatchSize,
			Page:   page,
		})
		if err != nil {
			return fmt.Errorf("apply daily interest: find accounts: %w", err)
		}

		for _, a := range accounts {
			_, applied, err := a.ApplyInterest(h.rate, today)
			if err != nil {
				return fmt.Errorf("apply daily interest: %w", err)
			}
			if !applied {
				continue // 오늘 이미 지급했거나(Level 1 멱등) 계산된 이자가 0인 계좌 — 스킵.
			}
			if err := h.repo.SaveAccount(ctx, a); err != nil {
				return fmt.Errorf("apply daily interest: save account: %w", err)
			}
		}

		if len(accounts) == 0 || (page+1)*interestBatchSize >= total {
			break
		}
	}
	return nil
}
