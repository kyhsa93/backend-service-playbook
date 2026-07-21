package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func newTransferAccounts() (source, target *account.Account) {
	source = account.New("owner-1", "owner-1@example.com", "KRW")
	_, _ = source.Deposit(10000, "")
	target = account.New("owner-2", "owner-2@example.com", "KRW")
	return source, target
}

func TestTransferHandler_Handle_SourceAccountNotFound(t *testing.T) {
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewTransferHandler(repo, stubTransactionManager{})

	_, err := handler.Handle(context.Background(), command.TransferCommand{
		SourceAccountID: "missing", TargetAccountID: "account-2", Amount: 1000,
	})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestTransferHandler_Handle_TargetAccountNotFound(t *testing.T) {
	source, _ := newTransferAccounts()
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			if accountID == source.AccountID {
				return source, nil
			}
			return nil, account.ErrNotFound
		},
	}
	handler := command.NewTransferHandler(repo, stubTransactionManager{})

	_, err := handler.Handle(context.Background(), command.TransferCommand{
		SourceAccountID: source.AccountID, TargetAccountID: "missing", Amount: 1000,
	})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestTransferHandler_Handle_승인되면_두_계좌를_저장한다(t *testing.T) {
	source, target := newTransferAccounts()
	var saved []string
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			if accountID == source.AccountID {
				return source, nil
			}
			return target, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error {
			saved = append(saved, a.AccountID)
			return nil
		},
	}
	handler := command.NewTransferHandler(repo, stubTransactionManager{})

	result, err := handler.Handle(context.Background(), command.TransferCommand{
		SourceAccountID: source.AccountID, TargetAccountID: target.AccountID, RequesterID: "owner-1", Amount: 4000,
	})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.SourceTransaction.Type != account.TransactionTypeWithdrawal {
		t.Fatalf("want WITHDRAWAL, got %v", result.SourceTransaction.Type)
	}
	if result.TargetTransaction.Type != account.TransactionTypeDeposit {
		t.Fatalf("want DEPOSIT, got %v", result.TargetTransaction.Type)
	}
	if result.SourceTransaction.ReferenceID != result.TransferID || result.TargetTransaction.ReferenceID != result.TransferID {
		t.Fatal("want both transactions to share transferID as ReferenceID")
	}
	if len(saved) != 2 {
		t.Fatalf("want 2 accounts saved, got %d", len(saved))
	}
}

func TestTransferHandler_Handle_잔액이_부족하면_저장하지_않는다(t *testing.T) {
	source, target := newTransferAccounts()
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			if accountID == source.AccountID {
				return source, nil
			}
			return target, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewTransferHandler(repo, stubTransactionManager{})

	_, err := handler.Handle(context.Background(), command.TransferCommand{
		SourceAccountID: source.AccountID, TargetAccountID: target.AccountID, Amount: 50000,
	})

	if !errors.Is(err, account.ErrInsufficientBalance) {
		t.Fatalf("want ErrInsufficientBalance, got %v", err)
	}
	if saveCalled {
		t.Fatal("want repo.Save not to be called when eligibility check fails")
	}
}

func TestTransferHandler_Handle_출금_입금_계좌가_같으면_저장하지_않는다(t *testing.T) {
	source, _ := newTransferAccounts()
	saveCalled := false
	repo := &stubRepository{
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return source, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewTransferHandler(repo, stubTransactionManager{})

	_, err := handler.Handle(context.Background(), command.TransferCommand{
		SourceAccountID: source.AccountID, TargetAccountID: source.AccountID, Amount: 1000,
	})

	if !errors.Is(err, account.ErrTransferSameAccount) {
		t.Fatalf("want ErrTransferSameAccount, got %v", err)
	}
	if saveCalled {
		t.Fatal("want repo.Save not to be called")
	}
}
