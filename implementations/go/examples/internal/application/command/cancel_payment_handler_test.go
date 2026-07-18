package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

func TestCancelPaymentHandler_Handle_PaymentNotFound(t *testing.T) {
	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return nil, 0, nil
		},
	}
	handler := command.NewCancelPaymentHandler(store, &stubOutboxRelay{})

	_, err := handler.Handle(context.Background(), command.CancelPaymentCommand{PaymentID: "missing", RequesterID: "owner-1", Reason: "customer request"})

	if !errors.Is(err, payment.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestCancelPaymentHandler_Handle_CancelsCompletedPayment(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()
	p.ClearEvents()

	saveCalled := false
	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return []*payment.Payment{p}, 1, nil
		},
		saveFn: func(ctx context.Context, saved *payment.Payment) error { saveCalled = true; return nil },
	}
	outboxRelay := &stubOutboxRelay{}
	handler := command.NewCancelPaymentHandler(store, outboxRelay)

	result, err := handler.Handle(context.Background(), command.CancelPaymentCommand{PaymentID: p.PaymentID, RequesterID: "owner-1", Reason: "customer request"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Status != payment.StatusCancelled {
		t.Fatalf("Status = %v, want StatusCancelled", result.Status)
	}
	if !saveCalled {
		t.Fatal("want store.Save to be called")
	}
	if outboxRelay.processed == 0 {
		t.Fatal("want outboxRelay.ProcessPending to be called at least once")
	}
}

func TestCancelPaymentHandler_Handle_PendingPaymentCannotBeCancelled(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	store := &stubPaymentStore{
		findPaymentsFn: func(ctx context.Context, q payment.FindQuery) ([]*payment.Payment, int, error) {
			return []*payment.Payment{p}, 1, nil
		},
	}
	handler := command.NewCancelPaymentHandler(store, &stubOutboxRelay{})

	_, err := handler.Handle(context.Background(), command.CancelPaymentCommand{PaymentID: p.PaymentID, RequesterID: "owner-1", Reason: "customer request"})

	if !errors.Is(err, payment.ErrCancelRequiresCompletedPayment) {
		t.Fatalf("want ErrCancelRequiresCompletedPayment, got %v", err)
	}
}
