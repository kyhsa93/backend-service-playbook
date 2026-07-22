package integrationevent

import "time"

// PaymentCancelledV1 is the Integration Event the Payment BC exposes to
// external BCs. It carries only the minimal information Account needs to
// execute a compensating credit (deposit).
type PaymentCancelledV1 struct {
	PaymentID   string    `json:"paymentId"`
	AccountID   string    `json:"accountId"`
	Amount      int64     `json:"amount"`
	CancelledAt time.Time `json:"cancelledAt"`
}

func (PaymentCancelledV1) EventName() string { return "payment.cancelled.v1" }
