package command

import (
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/payment"
)

// bad — the Payment BC's Command Handler directly imports the Account BC's
// domain.Repository (bypassing the Adapter). Violates
// cross-domain-communication.md.
type CreatePaymentHandler struct {
	paymentRepo payment.Repository
	accountRepo account.Repository
}
