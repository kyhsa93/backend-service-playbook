package account_test

import (
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/example/account-service/internal/domain/account"
)

var hex32 = regexp.MustCompile(`^[0-9a-f]{32}$`)

func TestNew(t *testing.T) {
	a := account.New("owner-1", "owner1@example.com", "KRW")

	if a.Status != account.StatusActive {
		t.Fatalf("Status = %v, want StatusActive", a.Status)
	}
	if a.Balance.Amount != 0 {
		t.Fatalf("Balance.Amount = %d, want 0", a.Balance.Amount)
	}
	if !hex32.MatchString(a.AccountID) {
		t.Fatalf("AccountID = %q, want 32-char hex string without hyphens", a.AccountID)
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
			_, err := a.Deposit(tt.amount, "")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Deposit() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestAccount_Deposit_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	a.ClearEvents()

	tx, err := a.Deposit(1000, "")
	if err != nil {
		t.Fatalf("Deposit() unexpected error: %v", err)
	}
	if !hex32.MatchString(tx.TransactionID) {
		t.Fatalf("TransactionID = %q, want 32-char hex string without hyphens", tx.TransactionID)
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
				_, _ = a.Deposit(5000, "")
				return a
			},
			amount:  0,
			wantErr: account.ErrInvalidAmount,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := tt.setup()
			_, err := a.Withdraw(tt.amount, "")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Withdraw() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestAccount_Withdraw_CollectsDomainEvent(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_, _ = a.Deposit(1000, "")
	a.ClearEvents()

	if _, err := a.Withdraw(400, ""); err != nil {
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
				_, _ = a.Deposit(1000, "")
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

func TestAccount_ApplyInterest(t *testing.T) {
	today := time.Date(2026, 7, 21, 0, 0, 0, 0, time.UTC)

	t.Run("정지된_계좌는_에러", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_ = a.Suspend()

		_, applied, err := a.ApplyInterest(0.01, today)
		if !errors.Is(err, account.ErrInterestRequiresActiveAccount) {
			t.Fatalf("ApplyInterest() error = %v, want ErrInterestRequiresActiveAccount", err)
		}
		if applied {
			t.Fatal("applied = true, want false")
		}
	})

	t.Run("잔액이_충분하면_이자가_지급되고_잔액에_반영된다", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_, _ = a.Deposit(1_000_000, "")
		a.ClearEvents()

		tx, applied, err := a.ApplyInterest(0.0001, today) // floor(1_000_000 * 0.0001) = 100
		if err != nil {
			t.Fatalf("ApplyInterest() unexpected error: %v", err)
		}
		if !applied {
			t.Fatal("applied = false, want true")
		}
		if tx.Type != account.TransactionTypeInterest {
			t.Fatalf("tx.Type = %v, want TransactionTypeInterest", tx.Type)
		}
		if tx.Amount.Amount != 100 {
			t.Fatalf("tx.Amount.Amount = %d, want 100", tx.Amount.Amount)
		}
		if a.Balance.Amount != 1_000_100 {
			t.Fatalf("Balance.Amount = %d, want 1000100", a.Balance.Amount)
		}
		if !a.LastInterestPaidAt.Equal(today) {
			t.Fatalf("LastInterestPaidAt = %v, want %v", a.LastInterestPaidAt, today)
		}

		events := a.DomainEvents()
		if len(events) != 1 {
			t.Fatalf("want 1 event, got %d", len(events))
		}
		if _, ok := events[0].(account.InterestPaid); !ok {
			t.Fatalf("want InterestPaid, got %T", events[0])
		}
	})

	t.Run("계산된_이자가_0이면_스킵되고_상태가_바뀌지_않는다", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_, _ = a.Deposit(10, "") // floor(10 * 0.0001) = 0
		a.ClearEvents()

		tx, applied, err := a.ApplyInterest(0.0001, today)
		if err != nil {
			t.Fatalf("ApplyInterest() unexpected error: %v", err)
		}
		if applied {
			t.Fatal("applied = true, want false")
		}
		if tx != (account.Transaction{}) {
			t.Fatalf("tx = %+v, want zero value", tx)
		}
		if a.Balance.Amount != 10 {
			t.Fatalf("Balance.Amount = %d, want unchanged 10", a.Balance.Amount)
		}
		if !a.LastInterestPaidAt.IsZero() {
			t.Fatal("LastInterestPaidAt should remain zero when interest is 0")
		}
		if len(a.DomainEvents()) != 0 {
			t.Fatalf("want 0 events, got %d", len(a.DomainEvents()))
		}
	})

	t.Run("같은_날짜에_재실행하면_멱등하게_스킵된다", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_, _ = a.Deposit(1_000_000, "")

		_, applied1, err := a.ApplyInterest(0.0001, today)
		if err != nil || !applied1 {
			t.Fatalf("first ApplyInterest() = (applied=%v, err=%v), want (true, nil)", applied1, err)
		}
		balanceAfterFirst := a.Balance.Amount
		a.ClearEvents()

		// at-least-once 재수신을 흉내낸 두 번째 호출 — 같은 날짜라 no-op이어야 한다.
		_, applied2, err := a.ApplyInterest(0.0001, today)
		if err != nil {
			t.Fatalf("second ApplyInterest() unexpected error: %v", err)
		}
		if applied2 {
			t.Fatal("second ApplyInterest() applied = true, want false (idempotent no-op)")
		}
		if a.Balance.Amount != balanceAfterFirst {
			t.Fatalf("Balance.Amount changed on second call: %d != %d", a.Balance.Amount, balanceAfterFirst)
		}
		if len(a.DomainEvents()) != 0 {
			t.Fatalf("want 0 events on idempotent no-op, got %d", len(a.DomainEvents()))
		}
	})

	t.Run("다음_날짜에는_다시_지급된다", func(t *testing.T) {
		a := account.New("owner-1", "a@example.com", "KRW")
		_, _ = a.Deposit(1_000_000, "")

		_, applied1, _ := a.ApplyInterest(0.0001, today)
		if !applied1 {
			t.Fatal("first ApplyInterest() should apply")
		}

		tomorrow := today.AddDate(0, 0, 1)
		_, applied2, err := a.ApplyInterest(0.0001, tomorrow)
		if err != nil {
			t.Fatalf("second ApplyInterest() unexpected error: %v", err)
		}
		if !applied2 {
			t.Fatal("second ApplyInterest() on a new day should apply")
		}
	})
}

func TestAccount_ClearTransactions(t *testing.T) {
	a := account.New("owner-1", "a@example.com", "KRW")
	_, _ = a.Deposit(1000, "")

	if len(a.PendingTransactions()) != 1 {
		t.Fatalf("want 1 pending transaction, got %d", len(a.PendingTransactions()))
	}

	a.ClearTransactions()
	if len(a.PendingTransactions()) != 0 {
		t.Fatalf("want 0 pending transactions after clear, got %d", len(a.PendingTransactions()))
	}
}
