package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/payment"
)

type GetRefundsQuery struct {
	PaymentID   string
	RequesterID string
	Page        int
	Take        int
}

// GetRefundsHandler — the Refund table itself has no OwnerID (a Refund
// references the original payment only via PaymentID) — ownership is
// verified by looking up the Payment first (the same pattern as
// account.Query.FindTransactions verifying account ownership first).
type GetRefundsHandler struct {
	payments payment.Query
	refunds  payment.RefundQuery
}

func NewGetRefundsHandler(payments payment.Query, refunds payment.RefundQuery) *GetRefundsHandler {
	return &GetRefundsHandler{payments: payments, refunds: refunds}
}

func (h *GetRefundsHandler) Handle(ctx context.Context, q GetRefundsQuery) (*GetRefundsResult, error) {
	if _, err := payment.FindOne(ctx, h.payments, q.PaymentID, q.RequesterID); err != nil {
		return nil, fmt.Errorf("get refunds: %w", err)
	}

	refunds, count, err := h.refunds.FindRefunds(ctx, payment.RefundFindQuery{
		PaymentID: q.PaymentID,
		Page:      q.Page,
		Take:      q.Take,
	})
	if err != nil {
		return nil, fmt.Errorf("get refunds: %w", err)
	}

	results := make([]GetRefundResult, len(refunds))
	for i, r := range refunds {
		results[i] = GetRefundResult{
			RefundID:     r.RefundID,
			PaymentID:    r.PaymentID,
			Amount:       r.Amount,
			Reason:       r.Reason,
			Status:       string(r.Status),
			DecisionNote: r.DecisionNote,
			CreatedAt:    r.CreatedAt,
		}
	}
	return &GetRefundsResult{Refunds: results, Count: count}, nil
}
