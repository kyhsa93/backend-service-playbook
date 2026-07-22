package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/payment"
)

// GetPaymentsQuery queries the list of payment history. No endpoint in this
// repository trusts an owner ID passed by the client — RequesterID is a
// value filled in by the auth middleware, and the list always returns only
// the requester's own payment history (there is no query parameter like
// ?ownerId=).
type GetPaymentsQuery struct {
	RequesterID string
	Page        int
	Take        int
}

type GetPaymentsHandler struct {
	payments payment.Query
}

func NewGetPaymentsHandler(payments payment.Query) *GetPaymentsHandler {
	return &GetPaymentsHandler{payments: payments}
}

func (h *GetPaymentsHandler) Handle(ctx context.Context, q GetPaymentsQuery) (*GetPaymentsResult, error) {
	payments, count, err := h.payments.FindPayments(ctx, payment.FindQuery{
		OwnerID: q.RequesterID,
		Page:    q.Page,
		Take:    q.Take,
	})
	if err != nil {
		return nil, fmt.Errorf("get payments: %w", err)
	}

	results := make([]GetPaymentResult, len(payments))
	for i, p := range payments {
		results[i] = GetPaymentResult{
			PaymentID: p.PaymentID,
			CardID:    p.CardID,
			AccountID: p.AccountID,
			OwnerID:   p.OwnerID,
			Amount:    p.Amount,
			Status:    string(p.Status),
			CreatedAt: p.CreatedAt,
		}
	}
	return &GetPaymentsResult{Payments: results, Count: count}, nil
}
