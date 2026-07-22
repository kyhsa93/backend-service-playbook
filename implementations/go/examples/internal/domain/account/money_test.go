package account_test

import (
	"errors"
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestNewMoney(t *testing.T) {
	tests := []struct {
		name    string
		amount  int64
		wantErr error
	}{
		{name: "negative_amount_errors", amount: -1, wantErr: account.ErrInvalidMoneyAmount},
		{name: "zero_amount_is_valid", amount: 0, wantErr: nil},
		{name: "positive_amount_is_valid", amount: 1000, wantErr: nil},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := account.NewMoney(tt.amount, "KRW")
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("NewMoney() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestMoney_Add(t *testing.T) {
	m1, _ := account.NewMoney(1000, "KRW")
	m2, _ := account.NewMoney(500, "KRW")

	result, err := m1.Add(m2)
	if err != nil {
		t.Fatalf("Add() unexpected error: %v", err)
	}
	if result.Amount != 1500 {
		t.Fatalf("Add() amount = %d, want 1500", result.Amount)
	}

	other, _ := account.NewMoney(500, "USD")
	if _, err := m1.Add(other); !errors.Is(err, account.ErrCurrencyMismatch) {
		t.Fatalf("Add() with different currency error = %v, want ErrCurrencyMismatch", err)
	}
}

func TestMoney_Subtract(t *testing.T) {
	m1, _ := account.NewMoney(1000, "KRW")
	m2, _ := account.NewMoney(300, "KRW")

	result, err := m1.Subtract(m2)
	if err != nil {
		t.Fatalf("Subtract() unexpected error: %v", err)
	}
	if result.Amount != 700 {
		t.Fatalf("Subtract() amount = %d, want 700", result.Amount)
	}

	other, _ := account.NewMoney(300, "USD")
	if _, err := m1.Subtract(other); !errors.Is(err, account.ErrCurrencyMismatch) {
		t.Fatalf("Subtract() with different currency error = %v, want ErrCurrencyMismatch", err)
	}
}

func TestMoney_LessThan(t *testing.T) {
	small, _ := account.NewMoney(100, "KRW")
	large, _ := account.NewMoney(200, "KRW")

	if !small.LessThan(large) {
		t.Fatal("want small.LessThan(large) to be true")
	}
	if large.LessThan(small) {
		t.Fatal("want large.LessThan(small) to be false")
	}
}

func TestMoney_IsZero(t *testing.T) {
	zero, _ := account.NewMoney(0, "KRW")
	nonZero, _ := account.NewMoney(1, "KRW")

	if !zero.IsZero() {
		t.Fatal("want zero.IsZero() to be true")
	}
	if nonZero.IsZero() {
		t.Fatal("want nonZero.IsZero() to be false")
	}
}

func TestMoney_Equals(t *testing.T) {
	a, _ := account.NewMoney(100, "KRW")
	b, _ := account.NewMoney(100, "KRW")
	c, _ := account.NewMoney(200, "KRW")

	if !a.Equals(b) {
		t.Fatal("want a.Equals(b) to be true")
	}
	if a.Equals(c) {
		t.Fatal("want a.Equals(c) to be false")
	}
}
