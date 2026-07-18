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
	handler := command.NewRequestRefundHandler(store, store, &stubOutboxRelay{})

	_, err := handler.Handle(context.Background(), command.RequestRefundCommand{PaymentID: "missing", Amount: 100, Reason: "wrong item", RequesterID: "owner-1"})

	if !errors.Is(err, payment.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

// TestRequestRefundHandler_Handle_ApprovesWithinCompletedPaymentAmount는
// RefundEligibilityService(Domain Service)가 승인 판단을 내렸을 때 이 Handler가
// refund.Approve()를 호출하고 저장·드레인까지 이어지는지 검증한다 — Handler 자체가
// 판단 로직을 재구현하지 않고 Domain Service에 위임하는지가 핵심이다.
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
	outboxRelay := &stubOutboxRelay{}
	handler := command.NewRequestRefundHandler(store, store, outboxRelay)

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
	if outboxRelay.processed == 0 {
		t.Fatal("want outboxRelay.ProcessPending to be called at least once")
	}
}

// TestRequestRefundHandler_Handle_RejectsWithoutError는 환불 거부가 에러가 아니라
// REJECTED 상태로 저장된 Refund를 정상 반환하는지 검증한다(도메인 관점에서 유효한
// 상태 전이 — Interface 레이어가 이를 201 + status:REJECTED로 응답한다).
func TestRequestRefundHandler_Handle_RejectsWithoutError(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()

	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return []*payment.Payment{p}, 1, nil
		},
	}
	handler := command.NewRequestRefundHandler(store, store, &stubOutboxRelay{})

	// 환불 금액(1500)이 결제 금액(1000)을 초과 — RefundEligibilityService가 거부한다.
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
