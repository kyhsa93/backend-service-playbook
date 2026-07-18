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

// CancelPaymentHandler는 결제취소를 처리한다. Payment.Cancel()이 발생시키는
// PaymentCancelled Domain Event가 payment.cancelled.v1 Integration Event로 변환되어
// Account BC가 구독해 보상 크레딧(이미 차감된 금액을 되돌림)을 실행한다.
type CancelPaymentHandler struct {
	repo   payment.Repository
	outbox OutboxRelay
}

func NewCancelPaymentHandler(repo payment.Repository, outbox OutboxRelay) *CancelPaymentHandler {
	return &CancelPaymentHandler{repo: repo, outbox: outbox}
}

func (h *CancelPaymentHandler) Handle(ctx context.Context, cmd CancelPaymentCommand) (*payment.Payment, error) {
	p, err := payment.FindOne(ctx, h.repo, cmd.PaymentID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}

	if err := p.Cancel(cmd.Reason); err != nil {
		return nil, err
	}

	if err := h.repo.Save(ctx, p); err != nil {
		return nil, err
	}
	if err := h.outbox.ProcessPending(ctx); err != nil {
		return nil, err
	}
	return p, nil
}
