package payment

type Refund struct {
	RefundID  string
	PaymentID string
	Amount    int64
}
