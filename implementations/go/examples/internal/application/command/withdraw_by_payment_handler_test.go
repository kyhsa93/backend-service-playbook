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
		// 실제 FindAccounts는 존재하지 않는 accountID를 조회해도 에러 없이 빈 결과를
		// 반환한다(SQL이 0행을 돌려줄 뿐이다) — account.ErrNotFound는 FindOne 헬퍼가
		// 그 빈 결과를 감싸 던지는 것이지 Repository/Query 레벨의 에러가 아니다.
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
	a := account.New("owner-1", "a@example.com", "KRW") // 잔액 0
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
