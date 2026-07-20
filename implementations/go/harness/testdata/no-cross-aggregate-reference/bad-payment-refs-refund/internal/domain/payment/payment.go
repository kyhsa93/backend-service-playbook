package payment

type Payment struct {
	PaymentID    string
	Amount       int64
	LinkedRefund *Refund
}
