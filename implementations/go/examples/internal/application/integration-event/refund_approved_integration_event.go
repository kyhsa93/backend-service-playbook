package integrationevent

import "time"

// RefundApprovedV1 is the Integration Event the Payment BC exposes to
// external BCs. It carries only the minimal information Account needs to
// execute a refund credit (deposit).
type RefundApprovedV1 struct {
	RefundID   string    `json:"refundId"`
	PaymentID  string    `json:"paymentId"`
	AccountID  string    `json:"accountId"`
	Amount     int64     `json:"amount"`
	ApprovedAt time.Time `json:"approvedAt"`
}

func (RefundApprovedV1) EventName() string { return "refund.approved.v1" }
