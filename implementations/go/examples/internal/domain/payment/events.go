package payment

import "time"

// DomainEvent is the marker interface shared by the two Payment/Refund
// Aggregates (the same idiom as account.DomainEvent). The Payment BC cannot
// load its own Domain Events through the shared outbox.Writer (whose
// signature is currently fixed to []account.DomainEvent), so
// Repository.Save inserts the outbox row directly within the same
// transaction instead (see
// infrastructure/persistence/payment_repository.go — the same workaround
// pattern used by the Repository that scripts/create-domain generates).
type DomainEvent interface {
	isPaymentDomainEvent()
}

// PaymentCompleted is raised when a payment is immediately marked complete
// (currently CreatePaymentHandler calls Complete() right after finishing
// its synchronous Adapter check, so there's no window where it stays
// PENDING). The Account BC subscribes to this event to perform the actual
// deduction (withdraw) asynchronously — it's translated into a thin
// Integration Event carrying only accountId+amount
// (payment.completed.v1), which
// application/event/payment_completed_event_handler.go loads into the
// Outbox.
type PaymentCompleted struct {
	PaymentID   string
	CardID      string
	AccountID   string
	OwnerID     string
	Amount      int64
	CompletedAt time.Time
}

func (PaymentCompleted) isPaymentDomainEvent() {}

// PaymentCancelled is raised when a completed payment is cancelled. The
// Account BC subscribes to it and executes a compensating credit (deposit)
// — a compensating transaction that reverses the amount already deducted.
type PaymentCancelled struct {
	PaymentID   string
	AccountID   string
	OwnerID     string
	Amount      int64
	Reason      string
	CancelledAt time.Time
}

func (PaymentCancelled) isPaymentDomainEvent() {}

// RefundApproved is raised when a refund is approved by
// RefundEligibilityService's judgment. Refund itself doesn't know
// accountId/ownerId (it references only paymentId), so when Approve() is
// called, this event is built carrying values obtained from the Payment
// that the Application layer has already loaded.
type RefundApproved struct {
	RefundID   string
	PaymentID  string
	AccountID  string
	OwnerID    string
	Amount     int64
	ApprovedAt time.Time
}

func (RefundApproved) isPaymentDomainEvent() {}
