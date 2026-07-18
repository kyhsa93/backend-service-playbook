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

// GetRefundsHandler — Refund 테이블 자체는 OwnerID를 갖지 않는다(Refund는 PaymentID로만
// 원 결제를 참조한다) — 소유권 검증은 Payment를 먼저 조회해 확인한다
// (account.Query.FindTransactions가 계좌 소유권을 먼저 확인하는 것과 동일한 패턴).
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
