package account_test

import (
	"errors"
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestNew(t *testing.T) {
	a := account.New("owner-1", "owner1@example.com", "KRW")

	if a.Status != account.StatusActive {
		t.Fatalf("Status = %v, want StatusActive", a.Status)
	}
	if a.Balance.Amount != 0 {
		t.Fatalf("Balance.Amount = %d, want 0", a.Balance.Amount)
	}
	events := a.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if _, ok := events[0].(account.AccountCreated); !ok {
		t.Fatalf("want AccountCreated, got %T", events[0])
	}
}

func TestAccount_Deposit(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *account.Account
		amount  int64
		wantErr error
	}{
		{
			name: "정지된_계좌에_입금하면_에러",
			setup: func() *account.Account {
				a := account.New("owner-1", "a@example.com", "KRW")
				_ = a.Suspend()
				return a
			},
			amount:  1000,
			wantErr: account.ErrDepositRequiresActiveAccount,
		},
		{
			name:    "0원_이하_입금은_에러",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			amount:  0,
			wantErr: account.ErrInvalidAmount,
		},
		{
			name:    "활성_계좌에_양수_금액_입금은_성공",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			amount:  1000,
			wantErr: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := tt.setup()
			_, err := a.Deposit(tt.amount)
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Deposit() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestAccount_Deposit_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents()

	if _, err := a.Deposit(1000); err != nil {
		t.Fatalf("Deposit() unexpected error: %v", err)
	}

	events := a.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if _, ok := events[0].(account.MoneyDeposited); !ok {
		t.Fatalf("want MoneyDeposited, got %T", events[0])
	}
	if a.Balance.Amount != 1000 {
		t.Fatalf("Balance.Amount = %d, want 1000", a.Balance.Amount)
	}
}

func TestAccount_Withdraw(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *account.Account
		amount  int64
		wantErr error
	}{
		{
			name: "정지된_계좌에서_출금하면_에러",
			setup: func() *account.Account {
				a := account.New("owner-1", "a@example.com", "KRW")
				_ = a.Suspend()
				return a
			},
			amount:  1000,
			wantErr: account.ErrWithdrawRequiresActiveAccount,
		},
		{
			name:    "잔액보다_큰_금액을_출금하면_에러",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			amount:  1000,
			wantErr: account.ErrInsufficientBalance,
		},
		{
			name: "0원_이하_출금은_에러",
			setup: func() *account.Account {
				a := account.New("owner-1", "a@example.com", "KRW")
				_, _ = a.Deposit(5000)
				return a
			},
			amount:  0,
			wantErr: account.ErrInvalidAmount,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := tt.setup()
			_, err := a.Withdraw(tt.amount)
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Withdraw() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestAccount_Withdraw_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_, _ = a.Deposit(1000)
	a.ClearEvents()

	if _, err := a.Withdraw(400); err != nil {
		t.Fatalf("Withdraw() unexpected error: %v", err)
	}

	events := a.DomainEvents()
	if len(events) != 1 {
		t.Fatalf("want 1 event, got %d", len(events))
	}
	if _, ok := events[0].(account.MoneyWithdrawn); !ok {
		t.Fatalf("want MoneyWithdrawn, got %T", events[0])
	}
	if a.Balance.Amount != 600 {
		t.Fatalf("Balance.Amount = %d, want 600", a.Balance.Amount)
	}
}

func TestAccount_Suspend(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")

	if err := a.Suspend(); err != nil {
		t.Fatalf("Suspend() unexpected error: %v", err)
	}
	if a.Status != account.StatusSuspended {
		t.Fatalf("Status = %v, want StatusSuspended", a.Status)
	}

	if err := a.Suspend(); !errors.Is(err, account.ErrSuspendRequiresActiveAccount) {
		t.Fatalf("Suspend() on already-suspended account error = %v, want ErrSuspendRequiresActiveAccount", err)
	}
}

func TestAccount_Reactivate(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")

	if err := a.Reactivate(); !errors.Is(err, account.ErrReactivateRequiresSuspendedAccount) {
		t.Fatalf("Reactivate() on active account error = %v, want ErrReactivateRequiresSuspendedAccount", err)
	}

	_ = a.Suspend()
	if err := a.Reactivate(); err != nil {
		t.Fatalf("Reactivate() unexpected error: %v", err)
	}
	if a.Status != account.StatusActive {
		t.Fatalf("Status = %v, want StatusActive", a.Status)
	}
}

func TestAccount_Close(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *account.Account
		wantErr error
	}{
		{
			name: "잔액이_0이_아니면_에러",
			setup: func() *account.Account {
				a := account.New("owner-1", "a@example.com", "KRW")
				_, _ = a.Deposit(1000)
				return a
			},
			wantErr: account.ErrBalanceNotZero,
		},
		{
			name:    "잔액이_0이면_성공",
			setup:   func() *account.Account { return account.New("owner-1", "a@example.com", "KRW") },
			wantErr: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := tt.setup()
			err := a.Close()
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Close() error = %v, want %v", err, tt.wantErr)
			}
		})
	}

	t.Run("이미_종료된_계좌를_다시_종료하면_에러", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_ = a.Close()
		if err := a.Close(); !errors.Is(err, account.ErrAlreadyClosed) {
			t.Fatalf("Close() error = %v, want ErrAlreadyClosed", err)
		}
	})
}

func TestAccount_ClearTransactions(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_, _ = a.Deposit(1000)

	if len(a.PendingTransactions()) != 1 {
		t.Fatalf("want 1 pending transaction, got %d", len(a.PendingTransactions()))
	}

	a.ClearTransactions()
	if len(a.PendingTransactions()) != 0 {
		t.Fatalf("want 0 pending transactions after clear, got %d", len(a.PendingTransactions()))
	}
}
