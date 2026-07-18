package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/payment"
)

type GetPaymentQuery struct {
	PaymentID   string
	RequesterID string
}

// GetPaymentHandler는 읽기 전용 payment.Query에만 의존한다(cqrs-pattern.md).
// 요청자(RequesterID)를 소유자로 매칭해 다른 사람의 결제는 조회되지 않는다.
type GetPaymentHandler struct {
	payments payment.Query
}

func NewGetPaymentHandler(payments payment.Query) *GetPaymentHandler {
	return &GetPaymentHandler{payments: payments}
}

func (h *GetPaymentHandler) Handle(ctx context.Context, q GetPaymentQuery) (*GetPaymentResult, error) {
	p, err := payment.FindOne(ctx, h.payments, q.PaymentID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get payment: %w", err)
	}
	return &GetPaymentResult{
		PaymentID: p.PaymentID,
		CardID:    p.CardID,
		AccountID: p.AccountID,
		OwnerID:   p.OwnerID,
		Amount:    p.Amount,
		Status:    string(p.Status),
		CreatedAt: p.CreatedAt,
	}, nil
}
