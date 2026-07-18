package integrationevent

import "time"

// RefundApprovedV1은 Payment BC가 외부 BC에 공개하는 Integration Event다. Account가
// 환불 크레딧(deposit)을 실행하는 데 필요한 최소 정보만 싣는다.
type RefundApprovedV1 struct {
	RefundID   string    `json:"refundId"`
	PaymentID  string    `json:"paymentId"`
	AccountID  string    `json:"accountId"`
	Amount     int64     `json:"amount"`
	ApprovedAt time.Time `json:"approvedAt"`
}

func (RefundApprovedV1) EventName() string { return "refund.approved.v1" }
