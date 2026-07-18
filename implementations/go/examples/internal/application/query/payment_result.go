package query

import "time"

type GetPaymentResult struct {
	PaymentID string
	CardID    string
	AccountID string
	OwnerID   string
	Amount    int64
	Status    string
	CreatedAt time.Time
}

type GetPaymentsResult struct {
	Payments []GetPaymentResult
	Count    int
}

type GetRefundResult struct {
	RefundID     string
	PaymentID    string
	Amount       int64
	Reason       string
	Status       string
	DecisionNote string
	CreatedAt    time.Time
}

type GetRefundsResult struct {
	Refunds []GetRefundResult
	Count   int
}
