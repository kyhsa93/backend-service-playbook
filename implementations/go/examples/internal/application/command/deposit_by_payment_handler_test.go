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

// TestDepositByPaymentHandler_Handle_DoesNotConfuseWithdrawalAndDepositOfSameReference는
// 이 저장소가 실제로 겪은 멱등성 버그(결제완료의 WITHDRAWAL과 그 결제취소 보상 크레딧인
// DEPOSIT이 같은 paymentId를 referenceId로 공유하므로, referenceId만으로 "이미 처리됨"을
// 판단하면 보상 크레딧이 잘못 스킵된다)를 회귀 테스트로 고정한다 — Handler가
// HasTransactionWithReference를 호출할 때 txType까지 함께 넘기는지 검증한다.
func TestDepositByPaymentHandler_Handle_ChecksReferenceScopedByType(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents()

	var checkedType account.TransactionType
	repo := &stubRepository{
		hasTransactionWithReferenceFn: func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
			checkedType = txType
			// 같은 paymentId의 WITHDRAWAL은 이미 있지만(결제완료 차감), DEPOSIT(보상
			// 크레딧)은 아직 없다 — false를 반환해야 보상 크레딧이 정상적으로 적용된다.
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
