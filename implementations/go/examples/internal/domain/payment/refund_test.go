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
			name:    "REQUESTED_환불을_승인하면_성공",
			setup:   func() *payment.Refund { return payment.NewRefund("payment-1", 500, "wrong item") },
			wantErr: nil,
		},
		{
			name: "이미_승인된_환불을_다시_승인하면_에러",
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
			name:    "REQUESTED_환불을_거부하면_성공",
			setup:   func() *payment.Refund { return payment.NewRefund("payment-1", 500, "wrong item") },
			wantErr: nil,
		},
		{
			name: "이미_거부된_환불을_다시_거부하면_에러",
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
			// Reject()는 도메인 이벤트를 발생시키지 않는다 — 승인 시(payment.completed.v1
			// 흐름의 대칭인 refund.approved.v1)만 외부 BC에 알릴 것이 있다.
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
			name: "APPROVED_환불을_완료하면_성공",
			setup: func() *payment.Refund {
				r := payment.NewRefund("payment-1", 500, "wrong item")
				_ = r.Approve("account-1", "owner-1")
				return r
			},
			wantErr: nil,
		},
		{
			name:    "REQUESTED_환불을_완료하면_에러",
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
