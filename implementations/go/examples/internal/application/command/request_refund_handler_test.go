package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

func TestRequestRefundHandler_Handle_PaymentNotFound(t *testing.T) {
	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return nil, 0, nil
		},
	}
	handler := command.NewRequestRefundHandler(store, store)

	_, err := handler.Handle(context.Background(), command.RequestRefundCommand{PaymentID: "missing", Amount: 100, Reason: "wrong item", RequesterID: "owner-1"})

	if !errors.Is(err, payment.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

// TestRequestRefundHandler_Handle_ApprovesWithinCompletedPaymentAmount
// verifies that when RefundEligibilityService (a Domain Service) decides to
// approve, this Handler calls refund.Approve() and follows through to
// saving/draining — the key point being that the Handler itself does not
// reimplement the decision logic but delegates to the Domain Service.
func TestRequestRefundHandler_Handle_ApprovesWithinCompletedPaymentAmount(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()

	var savedRefund *payment.Refund
	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return []*payment.Payment{p}, 1, nil
		},
		saveRefundFn: func(ctx context.Context, r *payment.Refund) error { savedRefund = r; return nil },
	}
	handler := command.NewRequestRefundHandler(store, store)

	result, err := handler.Handle(context.Background(), command.RequestRefundCommand{PaymentID: p.PaymentID, Amount: 500, Reason: "wrong item", RequesterID: "owner-1"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Status != payment.RefundStatusApproved {
		t.Fatalf("Status = %v, want RefundStatusApproved", result.Status)
	}
	if savedRefund != result {
		t.Fatal("want store.SaveRefund to be called with the created refund")
	}
}

// TestRequestRefundHandler_Handle_RejectsWithoutError verifies that a refund
// rejection is not an error but returns a normally saved Refund with
// REJECTED status (a valid state transition from the domain's perspective —
// the Interface layer responds to this as 201 + status:REJECTED).
func TestRequestRefundHandler_Handle_RejectsWithoutError(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()

	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return []*payment.Payment{p}, 1, nil
		},
	}
	handler := command.NewRequestRefundHandler(store, store)

	// The refund amount (1500) exceeds the payment amount (1000) — RefundEligibilityService rejects it.
	result, err := handler.Handle(context.Background(), command.RequestRefundCommand{PaymentID: p.PaymentID, Amount: 1500, Reason: "wrong item", RequesterID: "owner-1"})

	if err != nil {
		t.Fatalf("want no error on rejection, got %v", err)
	}
	if result.Status != payment.RefundStatusRejected {
		t.Fatalf("Status = %v, want RefundStatusRejected", result.Status)
	}
	if result.DecisionNote != payment.ErrRefundAmountExceedsPayment.Error() {
		t.Fatalf("DecisionNote = %q, want %q", result.DecisionNote, payment.ErrRefundAmountExceedsPayment.Error())
	}
}
