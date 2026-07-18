package payment_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/payment"
)

// TestEvaluateRefundEligibility는 root docs/architecture/domain-service.md가 요구하는
// "여러 Aggregate를 조율하는 순수 도메인 로직"을 Payment+Refund 두 Aggregate 조합으로
// 직접 검증한다 — 어느 한쪽 Aggregate의 단위 테스트만으로는 이 판단을 검증할 수 없다.
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
			name:         "완료된_결제에_결제금액_이하_환불은_승인",
			payment:      completedPayment(1000),
			refundAmount: 1000,
			wantApproved: true,
		},
		{
			name:         "완료된_결제에_결제금액보다_적은_환불도_승인",
			payment:      completedPayment(1000),
			refundAmount: 400,
			wantApproved: true,
		},
		{
			name:         "완료되지_않은_결제는_거부",
			payment:      pendingPayment(1000),
			refundAmount: 500,
			wantApproved: false,
			wantReason:   payment.ErrRefundRequiresCompletedPayment,
		},
		{
			name:         "환불_금액이_결제_금액을_초과하면_거부",
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
