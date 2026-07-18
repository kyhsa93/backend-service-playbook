package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/payment"
)

// GetPaymentsQuery는 결제 내역 목록을 조회한다. 이 저장소의 어떤 엔드포인트도 클라이언트가
// 넘긴 소유자 ID를 신뢰하지 않는다 — RequesterID는 인증 미들웨어가 채운 값이며, 목록은
// 항상 요청자 자신의 결제 내역만 반환한다(?ownerId= 같은 쿼리 파라미터는 없다).
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
