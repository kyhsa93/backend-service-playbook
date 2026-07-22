package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

// TestEvaluateTransferEligibility directly verifies "pure domain logic that
// coordinates multiple Aggregate instances," as required by the root
// docs/architecture/domain-service.md, using a combination of two Account
// instances — this judgment cannot be verified by a unit test of either
// Account alone.
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
			name:      "sufficient_source_balance_is_approved",
			source:    fundedAccount("KRW", 10000),
			target:    fundedAccount("KRW", 0),
			amount:    5000,
			wantApprv: true,
		},
		{
			name:       "same_source_and_target_account_is_rejected",
			source:     fundedAccount("KRW", 10000),
			sameAcctID: true,
			amount:     5000,
			wantErr:    account.ErrTransferSameAccount,
		},
		{
			name:    "inactive_source_account_is_rejected",
			source:  suspendedAccount("KRW", 10000),
			target:  fundedAccount("KRW", 0),
			amount:  5000,
			wantErr: account.ErrWithdrawRequiresActiveAccount,
		},
		{
			name:    "inactive_target_account_is_rejected",
			source:  fundedAccount("KRW", 10000),
			target:  suspendedAccount("KRW", 0),
			amount:  5000,
			wantErr: account.ErrDepositRequiresActiveAccount,
		},
		{
			name:    "mismatched_currency_is_rejected",
			source:  fundedAccount("KRW", 10000),
			target:  fundedAccount("USD", 0),
			amount:  5000,
			wantErr: account.ErrCurrencyMismatch,
		},
		{
			name:    "insufficient_source_balance_is_rejected",
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
