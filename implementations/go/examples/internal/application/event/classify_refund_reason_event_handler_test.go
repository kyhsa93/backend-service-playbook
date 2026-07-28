package event_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	appevent "github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/domain/payment"
)

type stubRefundReasonClassifier struct {
	called bool
	fn     func(ctx context.Context, reason string) payment.RefundReasonCategory
}

func (s *stubRefundReasonClassifier) Classify(ctx context.Context, reason string) payment.RefundReasonCategory {
	s.called = true
	return s.fn(ctx, reason)
}

type stubRefundRepository struct {
	findRefundsFn func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error)
	saveFn        func(ctx context.Context, r *payment.Refund) error
	findCalled    bool
	saveCalled    bool
	savedRefund   *payment.Refund
}

func (s *stubRefundRepository) FindRefunds(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
	s.findCalled = true
	return s.findRefundsFn(ctx, q)
}

func (s *stubRefundRepository) SaveRefund(ctx context.Context, r *payment.Refund) error {
	s.saveCalled = true
	s.savedRefund = r
	if s.saveFn != nil {
		return s.saveFn(ctx, r)
	}
	return nil
}

func refundRequestedPayload(t *testing.T, evt payment.RefundRequested) []byte {
	t.Helper()
	body, err := json.Marshal(evt)
	if err != nil {
		t.Fatalf("marshal RefundRequested: %v", err)
	}
	return body
}

func TestClassifyRefundReasonEventHandler_Handle_WhenRefundStillExists_ClassifiesAndSaves(t *testing.T) {
	refund := payment.ReconstituteRefund("refund-1", "payment-1", 5000, "The item arrived broken", payment.RefundStatusApproved, "", "", payment.NewRefund("payment-1", 5000, "x").CreatedAt)
	classifier := &stubRefundReasonClassifier{fn: func(ctx context.Context, reason string) payment.RefundReasonCategory {
		if reason != "The item arrived broken" {
			t.Fatalf("Classify() called with %q, want %q", reason, "The item arrived broken")
		}
		return payment.RefundReasonCategoryDefectiveProduct
	}}
	repo := &stubRefundRepository{findRefundsFn: func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
		if q.RefundID != "refund-1" {
			t.Fatalf("FindRefunds called with RefundID=%q, want refund-1", q.RefundID)
		}
		return []*payment.Refund{refund}, 1, nil
	}}
	handler := appevent.NewClassifyRefundReasonEventHandler(classifier, repo)

	payload := refundRequestedPayload(t, payment.RefundRequested{RefundID: "refund-1", PaymentID: "payment-1", Reason: "The item arrived broken"})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !classifier.called {
		t.Fatal("want classifier.Classify to be called")
	}
	if !repo.saveCalled {
		t.Fatal("want repo.SaveRefund to be called")
	}
	if repo.savedRefund.ReasonCategory != payment.RefundReasonCategoryDefectiveProduct {
		t.Fatalf("saved ReasonCategory = %v, want DEFECTIVE_PRODUCT", repo.savedRefund.ReasonCategory)
	}
}

func TestClassifyRefundReasonEventHandler_Handle_WhenRefundNoLongerExists_SkipsWithoutError(t *testing.T) {
	classifier := &stubRefundReasonClassifier{fn: func(ctx context.Context, reason string) payment.RefundReasonCategory {
		t.Fatal("Classify must not be called")
		return ""
	}}
	repo := &stubRefundRepository{findRefundsFn: func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
		return nil, 0, nil
	}}
	handler := appevent.NewClassifyRefundReasonEventHandler(classifier, repo)

	payload := refundRequestedPayload(t, payment.RefundRequested{RefundID: "refund-1", PaymentID: "payment-1", Reason: "x"})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if classifier.called {
		t.Fatal("want classifier.Classify NOT to be called")
	}
	if repo.saveCalled {
		t.Fatal("want repo.SaveRefund NOT to be called")
	}
}

func TestClassifyRefundReasonEventHandler_Handle_WhenFindFails_PropagatesErrorForRedelivery(t *testing.T) {
	repo := &stubRefundRepository{findRefundsFn: func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
		return nil, 0, errors.New("db unavailable")
	}}
	handler := appevent.NewClassifyRefundReasonEventHandler(&stubRefundReasonClassifier{fn: func(ctx context.Context, reason string) payment.RefundReasonCategory {
		t.Fatal("Classify must not be called")
		return ""
	}}, repo)

	payload := refundRequestedPayload(t, payment.RefundRequested{RefundID: "refund-1", PaymentID: "payment-1", Reason: "x"})
	if err := handler.Handle(context.Background(), payload); err == nil {
		t.Fatal("want error to propagate so the message is left unacked for redelivery")
	}
}

// TestClassifyRefundReasonEventHandler_Handle_ClassifiesARejectedRefundJustLikeAnApproved
// is the key design-point regression test: classification must run (and be
// saved) identically whether the refund is REJECTED or APPROVED — it never
// reads the eligibility outcome at all.
func TestClassifyRefundReasonEventHandler_Handle_ClassifiesARejectedRefundJustLikeAnApproved(t *testing.T) {
	refund := payment.ReconstituteRefund("refund-1", "payment-1", 5000, "wrong item", payment.RefundStatusRejected, "refund amount exceeds payment amount", "", payment.NewRefund("payment-1", 5000, "x").CreatedAt)
	classifier := &stubRefundReasonClassifier{fn: func(ctx context.Context, reason string) payment.RefundReasonCategory {
		return payment.RefundReasonCategoryWrongItem
	}}
	repo := &stubRefundRepository{findRefundsFn: func(ctx context.Context, q payment.RefundFindQuery) ([]*payment.Refund, int, error) {
		return []*payment.Refund{refund}, 1, nil
	}}
	handler := appevent.NewClassifyRefundReasonEventHandler(classifier, repo)

	payload := refundRequestedPayload(t, payment.RefundRequested{RefundID: "refund-1", PaymentID: "payment-1", Reason: "wrong item"})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !repo.saveCalled || repo.savedRefund.ReasonCategory != payment.RefundReasonCategoryWrongItem {
		t.Fatalf("want the REJECTED refund classified and saved just like an approved one, got saveCalled=%v category=%v", repo.saveCalled, repo.savedRefund)
	}
}
