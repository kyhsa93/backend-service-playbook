package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

// TestEvaluateTransferEligibility는 root docs/architecture/domain-service.md가 요구하는
// "여러 Aggregate 인스턴스를 조율하는 순수 도메인 로직"을 두 Account 인스턴스 조합으로
// 직접 검증한다 — 어느 한쪽 Account의 단위 테스트만으로는 이 판단을 검증할 수 없다.
func TestEvaluateTransferEligibility(t *testing.T) {
	fundedAccount := func(currency string, amount int64) *account.Account {
		a := account.New("owner-1", "a@example.com", currency)
		if amount > 0 {
			_, _ = a.Deposit(amount, "")
		}
		return a
	}
	suspendedAccount := func(currency string, amount int64) *account.Account {
		a := fundedAccount(currency, amount)
		_ = a.Suspend()
		return a
	}

	tests := []struct {
		name       string
		source     *account.Account
		target     *account.Account
		amount     int64
		wantApprv  bool
		wantErr    error
		sameAcctID bool
	}{
		{
			name:      "출금_계좌_잔액이_충분하면_승인",
			source:    fundedAccount("KRW", 10000),
			target:    fundedAccount("KRW", 0),
			amount:    5000,
			wantApprv: true,
		},
		{
			name:       "출금_입금_계좌가_같으면_거부",
			source:     fundedAccount("KRW", 10000),
			sameAcctID: true,
			amount:     5000,
			wantErr:    account.ErrTransferSameAccount,
		},
		{
			name:    "출금_계좌가_비활성이면_거부",
			source:  suspendedAccount("KRW", 10000),
			target:  fundedAccount("KRW", 0),
			amount:  5000,
			wantErr: account.ErrWithdrawRequiresActiveAccount,
		},
		{
			name:    "입금_계좌가_비활성이면_거부",
			source:  fundedAccount("KRW", 10000),
			target:  suspendedAccount("KRW", 0),
			amount:  5000,
			wantErr: account.ErrDepositRequiresActiveAccount,
		},
		{
			name:    "통화가_일치하지_않으면_거부",
			source:  fundedAccount("KRW", 10000),
			target:  fundedAccount("USD", 0),
			amount:  5000,
			wantErr: account.ErrCurrencyMismatch,
		},
		{
			name:    "출금_계좌_잔액이_부족하면_거부",
			source:  fundedAccount("KRW", 1000),
			target:  fundedAccount("KRW", 0),
			amount:  5000,
			wantErr: account.ErrInsufficientBalance,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			target := tt.target
			if tt.sameAcctID {
				target = tt.source
			}
			decision := account.EvaluateTransferEligibility(tt.source, target, tt.amount)

			if decision.Approved != tt.wantApprv {
				t.Fatalf("Approved = %v, want %v", decision.Approved, tt.wantApprv)
			}
			if !tt.wantApprv && decision.Err != tt.wantErr {
				t.Fatalf("Err = %v, want %v", decision.Err, tt.wantErr)
			}
			if tt.wantApprv && decision.Err != nil {
				t.Fatalf("Err = %v, want nil on approval", decision.Err)
			}
		})
	}
}
