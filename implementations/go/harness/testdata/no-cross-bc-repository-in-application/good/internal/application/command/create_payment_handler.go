package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCardAdapter is an ACL Adapter that queries the Card BC through its own
// View instead of importing its domain package directly — an example of this
// file importing only a single BC's domain (payment).
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
