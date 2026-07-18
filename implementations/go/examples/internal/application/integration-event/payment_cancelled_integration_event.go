package integrationevent

import "time"

// PaymentCancelledV1은 Payment BC가 외부 BC에 공개하는 Integration Event다. Account가
// 보상 크레딧(deposit)을 실행하는 데 필요한 최소 정보만 싣는다.
type PaymentCancelledV1 struct {
	PaymentID   string    `json:"paymentId"`
	AccountID   string    `json:"accountId"`
	Amount      int64     `json:"amount"`
	CancelledAt time.Time `json:"cancelledAt"`
}

func (PaymentCancelledV1) EventName() string { return "payment.cancelled.v1" }
