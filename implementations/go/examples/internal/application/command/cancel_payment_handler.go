package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

type CancelPaymentCommand struct {
	PaymentID   string
	Reason      string
	RequesterID string
}

// CancelPaymentHandler processes a payment cancellation. The PaymentCancelled
// Domain Event raised by Payment.Cancel() is translated into a
// payment.cancelled.v1 Integration Event, which the Account BC subscribes to
// in order to run a compensating credit (reversing the amount already debited).
type CancelPaymentHandler struct {
	repo payment.Repository
}

func NewCancelPaymentHandler(repo payment.Repository) *CancelPaymentHandler {
	return &CancelPaymentHandler{repo: repo}
}

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *CancelPaymentHandler) Handle(ctx context.Context, cmd CancelPaymentCommand) (*payment.Payment, error) {
	p, err := payment.FindOne(ctx, h.repo, cmd.PaymentID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}

	if err := p.Cancel(cmd.Reason); err != nil {
		return nil, err
	}

	if err := h.repo.SavePayment(ctx, p); err != nil {
		return nil, err
	}
	return p, nil
}
