package command

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/account"
)

// ApplyDailyInterestCommand is the input InterestTaskController (interface/task/)
// builds by deserializing the Task Queue message payload. Date is in
// "2006-01-02" format, and InterestScheduler.EnqueueDailyInterest sends the
// date as of enqueue time as-is in the payload — so even if the Consumer
// processes this message later (past midnight), "which date's interest this
// is" stays fixed.
type ApplyDailyInterestCommand struct {
	Date string
}

// ApplyDailyInterestHandler is a system-triggered use case driven by the Task
// Queue — since it is not a user command, it does not take authorization
// info such as RequesterID. It pages through all ACTIVE accounts and calls
// Account.ApplyInterest (Level 1 idempotent) on each.
type ApplyDailyInterestHandler struct {
	repo account.Repository
	rate float64
}

func NewApplyDailyInterestHandler(repo account.Repository, rate float64) *ApplyDailyInterestHandler {
	return &ApplyDailyInterestHandler{repo: repo, rate: rate}
}

// interestBatchSize is the number of accounts fetched per page. It is set
// smaller than the Take:1000 idiom used by existing batch-style handlers
// (e.g. suspend_cards_by_account_handler.go) so that per-page query/processing
// time doesn't grow excessive in environments with a large number of accounts.
const interestBatchSize = 500

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md). The InterestPaid Domain Event raised by
// Account.ApplyInterest rides this same path — the Task Queue (this batch
// itself) and the Domain Event (the resulting fact of interest having been
// paid) are two distinct mechanisms used together within a single use case.
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
				continue // Skip: interest already paid today (Level 1 idempotent) or computed interest is 0.
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
