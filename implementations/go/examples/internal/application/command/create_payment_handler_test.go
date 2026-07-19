package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/payment"
)

func TestCreatePaymentHandler_Handle(t *testing.T) {
	activeCard := &command.PaymentCardView{CardID: "card-1", AccountID: "account-1", Active: true}
	activeAccount := &command.PaymentAccountView{AccountID: "account-1", Active: true, Balance: 10000, Currency: "KRW"}

	tests := []struct {
		name    string
		cards   *stubPaymentCardAdapter
		accts   *stubPaymentAccountAdapter
		amount  int64
		wantErr error
	}{
		{
			name:    "카드를_찾을_수_없으면_에러",
			cards:   &stubPaymentCardAdapter{findCardFn: func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) { return nil, nil }},
			accts:   &stubPaymentAccountAdapter{},
			amount:  1000,
			wantErr: payment.ErrLinkedCardNotFound,
		},
		{
			name: "카드가_비활성이면_에러",
			cards: &stubPaymentCardAdapter{findCardFn: func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
				return &command.PaymentCardView{CardID: "card-1", AccountID: "account-1", Active: false}, nil
			}},
			accts:   &stubPaymentAccountAdapter{},
			amount:  1000,
			wantErr: payment.ErrRequiresActiveCard,
		},
		{
			name: "계좌를_찾을_수_없으면_에러",
			cards: &stubPaymentCardAdapter{findCardFn: func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
				return activeCard, nil
			}},
			accts: &stubPaymentAccountAdapter{findAccountFn: func(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
				return nil, nil
			}},
			amount:  1000,
			wantErr: payment.ErrLinkedAccountNotFound,
		},
		{
			name: "잔액이_부족하면_에러",
			cards: &stubPaymentCardAdapter{findCardFn: func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
				return activeCard, nil
			}},
			accts: &stubPaymentAccountAdapter{findAccountFn: func(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
				return &command.PaymentAccountView{AccountID: "account-1", Active: true, Balance: 100, Currency: "KRW"}, nil
			}},
			amount:  1000,
			wantErr: payment.ErrInsufficientBalance,
		},
		{
			name: "카드_활성_계좌_잔액_충분하면_성공",
			cards: &stubPaymentCardAdapter{findCardFn: func(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
				return activeCard, nil
			}},
			accts: &stubPaymentAccountAdapter{findAccountFn: func(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
				return activeAccount, nil
			}},
			amount:  1000,
			wantErr: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			store := &stubPaymentStore{}
			handler := command.NewCreatePaymentHandler(store, tt.cards, tt.accts)

			p, err := handler.Handle(context.Background(), command.CreatePaymentCommand{CardID: "card-1", Amount: tt.amount, RequesterID: "owner-1"})

			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Handle() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil {
				if p.Status != payment.StatusCompleted {
					t.Fatalf("Status = %v, want StatusCompleted", p.Status)
				}
			}
		})
	}
}
