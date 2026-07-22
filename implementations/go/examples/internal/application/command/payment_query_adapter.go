package command

import (
	"context"
	"time"
)

// CardPaymentSummary is the minimal information the Card BC needs for
// sending monthly usage statements (count, total amount) — it does not
// expose Payment's domain model (the list of Payments, their statuses, etc.) directly.
type CardPaymentSummary struct {
	Count       int
	TotalAmount int64
}

// PaymentQueryAdapter is the port (ACL interface) the Card BC uses to
// synchronously query the Payment BC. It is used only for
// SendCardUsageStatementHandler to obtain a usage summary for a period —
// Card's Application/Domain layers never import the payment package at all
// (cross-domain-communication.md; the same pattern as
// PaymentCardAdapter/PaymentAccountAdapter applied in the reverse direction).
// The implementation lives in infrastructure/acl.
type PaymentQueryAdapter interface {
	SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (CardPaymentSummary, error)
}
