package payment_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/payment"
)

// TestEvaluateRefundEligibility directly verifies "pure domain logic that
// coordinates multiple Aggregates," as required by the root
// docs/architecture/domain-service.md, using a combination of the two
// Payment+Refund Aggregates — this judgment cannot be verified by a unit
// test of either Aggregate alone.
func TestEvaluateRefundEligibility(t *testing.T) {
	completedPayment := func(amount int64) *payment.Payment {
		p := payment.New("card-1", "account-1", "owner-1", amount)
		_ = p.Complete()
		return p
	}
	pendingPayment := func(amount int64) *payment.Payment {
		return payment.New("card-1", "account-1", "owner-1", amount)
	}

	tests := []struct {
		name         string
		payment      *payment.Payment
		refundAmount int64
		wantApproved bool
		wantReason   error
	}{
		{
			name:         "refund_up_to_the_payment_amount_on_a_completed_payment_is_approved",
			payment:      completedPayment(1000),
			refundAmount: 1000,
			wantApproved: true,
		},
		{
			name:         "a_refund_less_than_the_payment_amount_on_a_completed_payment_is_also_approved",
			payment:      completedPayment(1000),
			refundAmount: 400,
			wantApproved: true,
		},
		{
			name:         "a_non_completed_payment_is_rejected",
			payment:      pendingPayment(1000),
			refundAmount: 500,
			wantApproved: false,
			wantReason:   payment.ErrRefundRequiresCompletedPayment,
		},
		{
			name:         "a_refund_amount_exceeding_the_payment_amount_is_rejected",
			payment:      completedPayment(1000),
			refundAmount: 1500,
			wantApproved: false,
			wantReason:   payment.ErrRefundAmountExceedsPayment,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := payment.NewRefund(tt.payment.PaymentID, tt.refundAmount, "reason")
			decision := payment.EvaluateRefundEligibility(tt.payment, r)

			if decision.Approved != tt.wantApproved {
				t.Fatalf("Approved = %v, want %v", decision.Approved, tt.wantApproved)
			}
			if !tt.wantApproved && decision.Reason != tt.wantReason.Error() {
				t.Fatalf("Reason = %q, want %q", decision.Reason, tt.wantReason.Error())
			}
			if tt.wantApproved && decision.Reason != "" {
				t.Fatalf("Reason = %q, want empty on approval", decision.Reason)
			}
		})
	}
}
