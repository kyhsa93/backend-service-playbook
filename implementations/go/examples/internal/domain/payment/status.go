package payment

// Status는 Payment Aggregate의 상태다. Refund는 별도의 RefundStatus를 쓴다(refund.go) —
// 두 Aggregate가 같은 패키지에 있어도 서로의 상태 enum을 공유하지 않는다(경계 유지).
type Status string

const (
	StatusPending   Status = "PENDING"
	StatusCompleted Status = "COMPLETED"
	StatusFailed    Status = "FAILED"
	StatusCancelled Status = "CANCELLED"
)

// RefundStatus는 Refund Aggregate의 상태다.
type RefundStatus string

const (
	RefundStatusRequested RefundStatus = "REQUESTED"
	RefundStatusApproved  RefundStatus = "APPROVED"
	RefundStatusRejected  RefundStatus = "REJECTED"
	RefundStatusCompleted RefundStatus = "COMPLETED"
)
