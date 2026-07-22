// CardPaymentAdapter is the Anticorruption Layer implementation used when
// the Card BC synchronously calls another Bounded Context (Payment). The
// interface (command.PaymentQueryAdapter) lives in the calling side's
// (Card) Application layer, and this implementation lives in the calling
// side's Infrastructure and imports the Payment domain — the dependency
// direction is "Card Infrastructure → Payment domain," and Card's
// Application/Domain know nothing about Payment at all (the same pattern
// as account_adapter.go and payment_adapters.go, applied in the reverse
// direction).
package acl

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

// paymentSummaryBatchSize is the page size SummarizeCardPayments uses when
// paging through the target period's payments.
const paymentSummaryBatchSize = 500

// CardPaymentAdapter is an ACL implementation satisfying
// command.PaymentQueryAdapter. It calls the read interface (payment.Query)
// exposed by the Payment BC and translates it into just the minimal
// summary (count/total) Card needs.
type CardPaymentAdapter struct {
	payments payment.Query
}

func NewCardPaymentAdapter(payments payment.Query) *CardPaymentAdapter {
	return &CardPaymentAdapter{payments: payments}
}

var _ command.PaymentQueryAdapter = (*CardPaymentAdapter)(nil)

// SummarizeCardPayments aggregates only the COMPLETED payments for cardID
// falling within the [from, to) range — PENDING/FAILED/CANCELLED payments
// were never actually charged, so they are not included in the "card usage
// statement."
func (a *CardPaymentAdapter) SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error) {
	var summary command.CardPaymentSummary
	for page := 0; ; page++ {
		payments, total, err := a.payments.FindPayments(ctx, payment.FindQuery{
			CardID:      cardID,
			Status:      []payment.Status{payment.StatusCompleted},
			CreatedFrom: from,
			CreatedTo:   to,
			Take:        paymentSummaryBatchSize,
			Page:        page,
		})
		if err != nil {
			return command.CardPaymentSummary{}, fmt.Errorf("card payment adapter summarize: %w", err)
		}
		for _, p := range payments {
			summary.Count++
			summary.TotalAmount += p.Amount
		}
		if len(payments) == 0 || (page+1)*paymentSummaryBatchSize >= total {
			break
		}
	}
	return summary, nil
}
