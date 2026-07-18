package payment_test

import (
	"errors"
	"regexp"
	"testing"

	"github.com/example/account-service/internal/domain/payment"
)

var hex32 = regexp.MustCompile(`^[0-9a-f]{32}$`)

func TestNew(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)

	if p.Status != payment.StatusPending {
		t.Fatalf("Status = %v, want StatusPending", p.Status)
	}
	if p.CardID != "card-1" || p.AccountID != "account-1" || p.OwnerID != "owner-1" || p.Amount != 1000 {
		t.Fatalf("unexpected payment fields: %+v", p)
	}
	if !hex32.MatchString(p.PaymentID) {
		t.Fatalf("PaymentID = %q, want 32-char hex string without hyphens", p.PaymentID)
	}
	if len(p.DomainEvents()) != 0 {
		t.Fatalf("want 0 events on New(), got %d", len(p.DomainEvents()))
	}
}

func TestPayment_Complete(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Payment
		wantErr error
	}{
		{
			name:    "PENDING_결제를_완료하면_성공",
			setup:   func() *payment.Payment { return payment.New("card-1", "account-1", "owner-1", 1000) },
			wantErr: nil,
		},
		{
			name: "이미_완료된_결제를_다시_완료하면_에러",
			setup: func() *payment.Payment {
				p := payment.New("card-1", "account-1", "owner-1", 1000)
				_ = p.Complete()
				return p
			},
			wantErr: payment.ErrCompleteRequiresPendingPayment,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := tt.setup()
			err := p.Complete()
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Complete() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestPayment_Complete_CollectsDomainEvent(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)

	if err := p.Complete(); err != nil {
		t.Fatalf("Complete() unexpected error: %v", err)
	}
	if p.Status != payment.StatusCompleted {
		t.Fatalf("Status = %v, want StatusCompleted", p.Status)
	}

	events := p.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	evt, ok := events[0].(payment.PaymentCompleted)
	if !ok {
		t.Fatalf("want PaymentCompleted, got %T", events[0])
	}
	if evt.AccountID != "account-1" || evt.Amount != 1000 {
		t.Fatalf("unexpected event fields: %+v", evt)
	}
}

func TestPayment_Fail(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Payment
		wantErr error
	}{
		{
			name:    "PENDING_결제를_실패처리하면_성공",
			setup:   func() *payment.Payment { return payment.New("card-1", "account-1", "owner-1", 1000) },
			wantErr: nil,
		},
		{
			name: "완료된_결제를_실패처리하면_에러",
			setup: func() *payment.Payment {
				p := payment.New("card-1", "account-1", "owner-1", 1000)
				_ = p.Complete()
				return p
			},
			wantErr: payment.ErrFailRequiresPendingPayment,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := tt.setup()
			err := p.Fail("gateway declined")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Fail() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil && p.Status != payment.StatusFailed {
				t.Fatalf("Status = %v, want StatusFailed", p.Status)
			}
		})
	}
}

func TestPayment_Cancel(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *payment.Payment
		wantErr error
	}{
		{
			name:    "PENDING_결제를_취소하면_에러",
			setup:   func() *payment.Payment { return payment.New("card-1", "account-1", "owner-1", 1000) },
			wantErr: payment.ErrCancelRequiresCompletedPayment,
		},
		{
			name: "완료된_결제를_취소하면_성공",
			setup: func() *payment.Payment {
				p := payment.New("card-1", "account-1", "owner-1", 1000)
				_ = p.Complete()
				return p
			},
			wantErr: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := tt.setup()
			err := p.Cancel("customer request")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Cancel() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil && p.Status != payment.StatusCancelled {
				t.Fatalf("Status = %v, want StatusCancelled", p.Status)
			}
		})
	}
}

func TestPayment_Cancel_CollectsDomainEvent(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()
	p.ClearEvents()

	if err := p.Cancel("customer request"); err != nil {
		t.Fatalf("Cancel() unexpected error: %v", err)
	}

	events := p.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	evt, ok := events[0].(payment.PaymentCancelled)
	if !ok {
		t.Fatalf("want PaymentCancelled, got %T", events[0])
	}
	if evt.Reason != "customer request" {
		t.Fatalf("Reason = %q, want %q", evt.Reason, "customer request")
	}
}

func TestPayment_ClearEvents(t *testing.T) {
	p := payment.New("card-1", "account-1", "owner-1", 1000)
	_ = p.Complete()

	if len(p.DomainEvents()) != 1 {
		t.Fatalf("want 1 event before clear, got %d", len(p.DomainEvents()))
	}
	p.ClearEvents()
	if len(p.DomainEvents()) != 0 {
		t.Fatalf("want 0 events after clear, got %d", len(p.DomainEvents()))
	}
}
