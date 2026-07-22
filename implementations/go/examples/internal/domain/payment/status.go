package payment

// Status is the state of the Payment Aggregate. Refund uses a separate
// RefundStatus (refund.go) — even though both Aggregates live in the same
// package, they don't share each other's state enum (boundary is
// preserved).
type Status string

const (
	StatusPending   Status = "PENDING"
	StatusCompleted Status = "COMPLETED"
	StatusFailed    Status = "FAILED"
	StatusCancelled Status = "CANCELLED"
)

// RefundStatus is the state of the Refund Aggregate.
type RefundStatus string

const (
	RefundStatusRequested RefundStatus = "REQUESTED"
	RefundStatusApproved  RefundStatus = "APPROVED"
	RefundStatusRejected  RefundStatus = "REJECTED"
	RefundStatusCompleted RefundStatus = "COMPLETED"
)
