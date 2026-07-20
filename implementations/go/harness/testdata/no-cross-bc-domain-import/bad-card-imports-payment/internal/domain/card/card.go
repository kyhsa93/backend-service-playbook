package card

import "github.com/example/account-service/internal/domain/payment"

type Card struct {
	CardID  string
	Payment *payment.Payment
}
