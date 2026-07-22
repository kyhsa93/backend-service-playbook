package command_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestDepositByPaymentHandler_Handle_SkipsIfAlreadyProcessed(t *testing.T) {
	saveCalled := false
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			return true, nil
		},
		saveFn: func(ctx context.Context, a *account.Account) error { saveCalled = true; return nil },
	}
	handler := command.NewDepositByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.DepositByPaymentCommand{AccountID: "account-1", Amount: 1000, ReferenceID: "payment-1"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if saveCalled {
		t.Fatal("want repo.Save NOT to be called when already processed (idempotency)")
	}
}

// TestDepositByPaymentHandler_Handle_DoesNotConfuseWithdrawalAndDepositOfSameReference
// pins down as a regression test an idempotency bug this repo actually hit
// (a payment-completion WITHDRAWAL and its payment-cancellation
// compensating-credit DEPOSIT share the same paymentId as referenceId, so
// judging "already processed" from referenceId alone would incorrectly skip
// the compensating credit) — it verifies that the Handler passes txType
// along when calling HasTransactionWithReference.
func TestDepositByPaymentHandler_Handle_ChecksReferenceScopedByType(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents()

	var checkedType account.TransactionType
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			checkedType = txType
			// A WITHDRAWAL already exists for this paymentId (the payment-completion
			// debit), but the DEPOSIT (compensating credit) does not yet — must return
			// false so the compensating credit is applied correctly.
			return false, nil
		},
		findByIDFn: func(ctx context.Context, accountID, ownerID string) (*account.Account, error) { return a, nil },
		saveFn:     func(ctx context.Context, saved *account.Account) error { return nil },
	}
	handler := command.NewDepositByPaymentHandler(repo)

	err := handler.Handle(context.Background(), command.DepositByPaymentCommand{AccountID: a.AccountID, Amount: 1000, ReferenceID: "payment-1"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if checkedType != account.TransactionTypeDeposit {
		t.Fatalf("HasTransactionWithReference checked type = %v, want %v", checkedType, account.TransactionTypeDeposit)
	}
	if a.Balance.Amount != 1000 {
		t.Fatalf("Balance.Amount = %d, want 1000 (compensating credit applied)", a.Balance.Amount)
	}
}
