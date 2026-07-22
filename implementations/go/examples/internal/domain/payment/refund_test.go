package payment_test

import (
	"errors"
	"testing"

	"github.com/example/account-service/internal/domain/payment"
)

func TestNewRefund(t *testing.T) {
	r := payment.NewRefund("payment-1", 500, "wrong item")

	if r.Status != payment.RefundStatusRequested {
		t.Fatalf("Status = %v, want RefundStatusRequested", r.Status)
	}
	if r.PaymentID != "payment-1" || r.Amount != 500 || r.Reason != "wrong item" {
		t.Fatalf("unexpected refund fields: %+v", r)
	}
	if !hex32.MatchString(r.RefundID) {
		t.Fatalf("RefundID = %q, want 32-char hex string without hyphens", r.RefundID)
	}
}

func TestRefund_Approve(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Refund
		wantErr error
	}{
		{
			name:    "approving_a_REQUESTED_refund_succeeds",
			setup:   func() *payment.Refund { return payment.NewRefund("payment-1", 500, "wrong item") },
			wantErr: nil,
		},
		{
			name: "approving_an_already_approved_refund_errors",
			setup: func() *payment.Refund {
				r := payment.NewRefund("payment-1", 500, "wrong item")
				_ = r.Approve("account-1", "owner-1")
				return r
			},
			wantErr: payment.ErrRefundApproveRequiresRequestedRefund,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := tt.setup()
			err := r.Approve("account-1", "owner-1")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Approve() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil && r.Status != payment.RefundStatusApproved {
				t.Fatalf("Status = %v, want RefundStatusApproved", r.Status)
			}
		})
	}
}

func TestRefund_Approve_CollectsDomainEvent(t *testing.T) {
	r := payment.NewRefund("payment-1", 500, "wrong item")

	if err := r.Approve("account-1", "owner-1"); err != nil {
		t.Fatalf("Approve() unexpected error: %v", err)
	}

	events := r.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	evt, ok := events[0].(payment.RefundApproved)
	if !ok {
		t.Fatalf("want RefundApproved, got %T", events[0])
	}
	if evt.AccountID != "account-1" || evt.OwnerID != "owner-1" || evt.Amount != 500 {
		t.Fatalf("unexpected event fields: %+v", evt)
	}
}

func TestRefund_Reject(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Refund
		wantErr error
	}{
		{
			name:    "rejecting_a_REQUESTED_refund_succeeds",
			setup:   func() *payment.Refund { return payment.NewRefund("payment-1", 500, "wrong item") },
			wantErr: nil,
		},
		{
			name: "rejecting_an_already_rejected_refund_errors",
			setup: func() *payment.Refund {
				r := payment.NewRefund("payment-1", 500, "wrong item")
				_ = r.Reject("policy violation")
				return r
			},
			wantErr: payment.ErrRefundRejectRequiresRequestedRefund,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := tt.setup()
			err := r.Reject("policy violation")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Reject() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil {
				if r.Status != payment.RefundStatusRejected {
					t.Fatalf("Status = %v, want RefundStatusRejected", r.Status)
				}
				if r.DecisionNote != "policy violation" {
					t.Fatalf("DecisionNote = %q, want %q", r.DecisionNote, "policy violation")
				}
			}
			// Reject() does not raise a domain event — only approval (via
			// refund.approved.v1, symmetric to the payment.completed.v1 flow)
			// has anything to notify an external BC about.
			if len(r.DomainEvents()) != 0 {
				t.Fatalf("want 0 events after Reject(), got %d", len(r.DomainEvents()))
			}
		})
	}
}

func TestRefund_Complete(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Refund
		wantErr error
	}{
		{
			name: "completing_an_APPROVED_refund_succeeds",
			setup: func() *payment.Refund {
				r := payment.NewRefund("payment-1", 500, "wrong item")
				_ = r.Approve("account-1", "owner-1")
				return r
			},
			wantErr: nil,
		},
		{
			name:    "completing_a_REQUESTED_refund_errors",
			setup:   func() *payment.Refund { return payment.NewRefund("payment-1", 500, "wrong item") },
			wantErr: payment.ErrRefundCompleteRequiresApprovedRefund,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := tt.setup()
			err := r.Complete()
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Complete() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil && r.Status != payment.RefundStatusCompleted {
				t.Fatalf("Status = %v, want RefundStatusCompleted", r.Status)
			}
		})
	}
}
