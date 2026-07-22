package command

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/card"
)

// SendCardUsageStatementCommand is the input StatementTaskController
// (interface/task/) builds by deserializing the Task Queue message payload.
// Period is in "2006-01" format, and StatementScheduler.EnqueueMonthlyStatement
// sends "last month" as of enqueue time as-is in the payload.
type SendCardUsageStatementCommand struct {
	Period string
}

// cardStatementBatchSize uses 500 for the same reason as interestBatchSize in
// apply_daily_interest_handler.go.
const cardStatementBatchSize = 500

// SendCardUsageStatementHandler is a system-triggered use case driven by the
// Task Queue. It iterates over all ACTIVE cards performing (1) linked
// account lookup -> (2) payment summary for the period -> (3) email
// delivery -> (4) saving an idempotency marker via Card.MarkStatementSent.
// Delivery (an external SES call) is a side effect not reflected in Card's
// state, so the marker must be saved only after delivery succeeds — that way,
// if delivery fails and this Handler returns an error, the Task is retried
// and delivery is actually attempted again (at-least-once, scheduling.md).
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

// Handle saves and returns immediately (no synchronous draining,
// domain-events.md) — Card does not raise a Domain Event in this use case
// (MarkStatementSent is a pure state-marker update), so it is unrelated to
// the Outbox path.
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
				continue // Already sent for this period (Level 1 idempotent) — skip.
			}

			view, err := h.accounts.FindAccount(ctx, c.AccountID, c.OwnerID)
			if err != nil {
				return fmt.Errorf("send card usage statement: %w", err)
			}
			if view == nil {
				continue // The linked account is gone — there is no one to notify, so silently ignore.
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

// periodRange converts a period in "2006-01" format into a half-open
// interval [from, to) — from is 00:00 UTC on the 1st of that month, and to
// is 00:00 UTC on the 1st of the next month.
func periodRange(period string) (time.Time, time.Time, error) {
	from, err := time.Parse("2006-01", period)
	if err != nil {
		return time.Time{}, time.Time{}, err
	}
	to := from.AddDate(0, 1, 0)
	return from, to, nil
}
