package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestDepositHandler_Handle_AccountNotFound(t *testing.T) {
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewDepositHandler(repo)

	_, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: "missing", Amount: 1000})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestDepositHandler_Handle_Saves(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn:     func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewDepositHandler(repo)

	if _, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: a.AccountID, Amount: 1000}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !saveCalled {
		t.Fatal("want repo.Save to be called")
	}
}

func TestDepositHandler_Handle_DomainErrorPropagates(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_ = a.Suspend()
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn:     func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewDepositHandler(repo)

	_, err := handler.Handle(context.Background(), command.DepositCommand{AccountID: a.AccountID, Amount: 1000})

	if !errors.Is(err, account.ErrDepositRequiresActiveAccount) {
		t.Fatalf("want ErrDepositRequiresActiveAccount, got %v", err)
	}
	if saveCalled {
		t.Fatal("want repo.Save not to be called when domain validation fails")
	}
}
