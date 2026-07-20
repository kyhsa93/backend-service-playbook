package command

import (
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/payment"
)

// bad — Payment BC의 Command Handler가 Account BC의 domain.Repository를 직접
// import함(Adapter를 거치지 않음). cross-domain-communication.md 위반 fixture.
type CreatePaymentHandler struct {
	paymentRepo payment.Repository
	accountRepo account.Repository
}
