package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCardAdapter는 Card BC를 domain 패키지를 직접 import하지 않고 자체 View로만
// 조회하는 ACL Adapter다 — 이 파일이 payment 하나의 BC domain만 import하는 예.
type PaymentCardAdapter interface {
	FindCard(ctx context.Context, cardID string) (*PaymentCardView, error)
}

type PaymentCardView struct {
	CardID string
	Active bool
}

type CreatePaymentHandler struct {
	repo payment.Repository
}
