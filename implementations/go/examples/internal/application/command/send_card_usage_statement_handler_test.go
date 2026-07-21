package command_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/card"
)

func TestSendCardUsageStatementHandler_Handle_InvalidPeriod(t *testing.T) {
	handler := command.NewSendCardUsageStatementHandler(
		&stubCardRepository{}, &stubAccountAdapter{}, &stubPaymentQueryAdapter{}, &stubStatementNotifier{},
	)

	err := handler.Handle(context.Background(), command.SendCardUsageStatementCommand{Period: "not-a-period"})

	if !errors.Is(err, card.ErrInvalidStatementPeriod) {
		t.Fatalf("want ErrInvalidStatementPeriod, got %v", err)
	}
}

func TestSendCardUsageStatementHandler_Handle_NotifiesAndMarksSent(t *testing.T) {
	c := card.IssueCard("acc-1", "owner-1", "VISA")

	var notified bool
	var saved *card.Card
	handler := command.NewSendCardUsageStatementHandler(
		&stubCardRepository{
			findAllFn: func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
				if q.Page > 0 {
					return nil, 1, nil
				}
				return []*card.Card{c}, 1, nil
			},
			saveFn: func(ctx context.Context, c *card.Card) error { saved = c; return nil },
		},
		&stubAccountAdapter{
			findAccountFn: func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
				return &command.AccountView{AccountID: accountID, Active: true, Email: "owner@example.com"}, nil
			},
		},
		&stubPaymentQueryAdapter{
			summarizeFn: func(ctx context.Context, cardID string, from, to time.Time) (command.CardPaymentSummary, error) {
				return command.CardPaymentSummary{Count: 3, TotalAmount: 15000}, nil
			},
		},
		&stubStatementNotifier{
			notifyFn: func(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error {
				notified = true
				if recipient != "owner@example.com" || paymentCount != 3 || totalAmount != 15000 || period != "2026-07" {
					t.Fatalf("unexpected notify args: recipient=%s count=%d total=%d period=%s", recipient, paymentCount, totalAmount, period)
				}
				return nil
			},
		},
	)

	err := handler.Handle(context.Background(), command.SendCardUsageStatementCommand{Period: "2026-07"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !notified {
		t.Fatal("want NotifyCardStatement to be called")
	}
	if saved == nil || saved.LastStatementSentMonth != "2026-07" {
		t.Fatalf("want SaveCard called with LastStatementSentMonth=2026-07, got %+v", saved)
	}
}

func TestSendCardUsageStatementHandler_Handle_SkipsAlreadySentPeriod(t *testing.T) {
	c := card.IssueCard("acc-1", "owner-1", "VISA")
	_ = c.MarkStatementSent("2026-07")

	var notifyCalled bool
	handler := command.NewSendCardUsageStatementHandler(
		&stubCardRepository{
			findAllFn: func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
				if q.Page > 0 {
					return nil, 1, nil
				}
				return []*card.Card{c}, 1, nil
			},
		},
		&stubAccountAdapter{},
		&stubPaymentQueryAdapter{},
		&stubStatementNotifier{
			notifyFn: func(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error {
				notifyCalled = true
				return nil
			},
		},
	)

	err := handler.Handle(context.Background(), command.SendCardUsageStatementCommand{Period: "2026-07"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if notifyCalled {
		t.Fatal("want notifier not called for an already-sent period (idempotent skip)")
	}
}

func TestSendCardUsageStatementHandler_Handle_SkipsMissingLinkedAccount(t *testing.T) {
	c := card.IssueCard("acc-1", "owner-1", "VISA")

	var notifyCalled bool
	handler := command.NewSendCardUsageStatementHandler(
		&stubCardRepository{
			findAllFn: func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
				if q.Page > 0 {
					return nil, 1, nil
				}
				return []*card.Card{c}, 1, nil
			},
		},
		&stubAccountAdapter{
			findAccountFn: func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
				return nil, nil // 계좌가 사라짐
			},
		},
		&stubPaymentQueryAdapter{},
		&stubStatementNotifier{
			notifyFn: func(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error {
				notifyCalled = true
				return nil
			},
		},
	)

	err := handler.Handle(context.Background(), command.SendCardUsageStatementCommand{Period: "2026-07"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if notifyCalled {
		t.Fatal("want notifier not called when linked account is gone")
	}
}
