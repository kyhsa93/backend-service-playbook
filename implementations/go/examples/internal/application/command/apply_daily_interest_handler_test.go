package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestApplyDailyInterestHandler_Handle_InvalidDate(t *testing.T) {
	repo := &stubRepository{}
	handler := command.NewApplyDailyInterestHandler(repo, 0.0001)

	err := handler.Handle(context.Background(), command.ApplyDailyInterestCommand{Date: "not-a-date"})

	if !errors.Is(err, account.ErrInvalidInterestDate) {
		t.Fatalf("want ErrInvalidInterestDate, got %v", err)
	}
}

func TestApplyDailyInterestHandler_Handle_AppliesInterestAndSaves(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")
	_, _ = a1.Deposit(1_000_000, "")
	a1.ClearEvents()

	a2 := account.New("owner-2", "a2@example.com", "KRW") // 잔액 0 — 이자 0이라 스킵되어야 함
	a2.ClearEvents()

	var saved []*account.Account
	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 2, nil
			}
			return []*account.Account{a1, a2}, 2, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error {
			saved = append(saved, a)
			return nil
		},
	}
	handler := command.NewApplyDailyInterestHandler(repo, 0.0001)

	err := handler.Handle(context.Background(), command.ApplyDailyInterestCommand{Date: "2026-07-21"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(saved) != 1 {
		t.Fatalf("want SaveAccount called once (only a1 has non-zero interest), got %d", len(saved))
	}
	if saved[0].AccountID != a1.AccountID {
		t.Fatalf("saved account = %s, want %s", saved[0].AccountID, a1.AccountID)
	}
	if a1.Balance.Amount != 1_000_100 {
		t.Fatalf("a1.Balance.Amount = %d, want 1000100", a1.Balance.Amount)
	}
}

func TestApplyDailyInterestHandler_Handle_PropagatesFindError(t *testing.T) {
	wantErr := errors.New("db down")
	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			return nil, 0, wantErr
		},
	}
	handler := command.NewApplyDailyInterestHandler(repo, 0.0001)

	err := handler.Handle(context.Background(), command.ApplyDailyInterestCommand{Date: "2026-07-21"})

	if !errors.Is(err, wantErr) {
		t.Fatalf("want wrapped %v, got %v", wantErr, err)
	}
}
