package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestWithdrawByPaymentHandler_Handle_SkipsIfAlreadyProcessed(t *testing.T) {
	saveCalled := false
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			return true, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewWithdrawByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.WithdrawByPaymentCommand{AccountID: "account-1", Amount: 1000, ReferenceID: "payment-1"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if saveCalled {
		t.Fatal("want repo.Save NOT to be called when already processed (idempotency)")
	}
}

func TestWithdrawByPaymentHandler_Handle_SkipsIfAccountMissing(t *testing.T) {
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			return false, nil
		},
		// The real FindAccounts returns an empty result with no error even when
		// queried with a nonexistent accountID (SQL simply returns 0 rows) —
		// account.ErrNotFound is thrown by the FindOne helper wrapping that
		// empty result; it is not a Repository/Query-level error.
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
			return nil, nil
		},
	}
	handler := command.NewWithdrawByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.WithdrawByPaymentCommand{AccountID: "missing", Amount: 1000, ReferenceID: "payment-1"})

	if err != nil {
		t.Fatalf("want nil (silently ignored) error, got %v", err)
	}
}

func TestWithdrawByPaymentHandler_Handle_WithdrawsAndSaves(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_, _ = a.Deposit(5000, "")
	a.ClearEvents()
	a.ClearTransactions()

	var savedReferenceID string
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			return false, nil
		},
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn: func(ctx context.Context, saved *account.Account) error {
			txs := saved.PendingTransactions()
			if len(txs) != 1 {
				t.Fatalf("want 1 pending transaction, got %d", len(txs))
			}
			savedReferenceID = txs[0].ReferenceID
			return nil
		},
	}
	handler := command.NewWithdrawByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.WithdrawByPaymentCommand{AccountID: a.AccountID, Amount: 1000, ReferenceID: "payment-1"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if a.Balance.Amount != 4000 {
		t.Fatalf("Balance.Amount = %d, want 4000", a.Balance.Amount)
	}
	if savedReferenceID != "payment-1" {
		t.Fatalf("ReferenceID = %q, want %q", savedReferenceID, "payment-1")
	}
}

func TestWithdrawByPaymentHandler_Handle_PropagatesDomainError(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW") // balance 0
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			return false, nil
		},
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
	}
	handler := command.NewWithdrawByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.WithdrawByPaymentCommand{AccountID: a.AccountID, Amount: 1000, ReferenceID: "payment-1"})

	if !errors.Is(err, account.ErrInsufficientBalance) {
		t.Fatalf("want ErrInsufficientBalance, got %v", err)
	}
}
