package integrationevent

import "time"

// PaymentCompletedV1 is the Integration Event (public contract) the Payment
// BC exposes to external BCs. It is a thin contract carrying only the
// minimal information Account needs for the actual debit (withdraw)
// (accountId+amount) — it does not expose Payment's internal model such as
// ownerId/cardId. PaymentID is also included as the correlation key the
// Account BC uses for its idempotency check (Level 2 Ledger: duplicate
// referenceId check).
type PaymentCompletedV1 struct {
	PaymentID   string    `json:"paymentId"`
	AccountID   string    `json:"accountId"`
	Amount      int64     `json:"amount"`
	CompletedAt time.Time `json:"completedAt"`
}

func (PaymentCompletedV1) EventName() string { return "payment.completed.v1" }
