package event_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	appevent "github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/domain/account"
)

type stubCategorizer struct {
	called bool
	fn     func(ctx context.Context, merchantName string, amount int64) account.TransactionCategory
}

func (s *stubCategorizer) Categorize(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
	s.called = true
	return s.fn(ctx, merchantName, amount)
}

type stubTransactionRepository struct {
	findFn     func(ctx context.Context, transactionID string) (*account.Transaction, error)
	saveFn     func(ctx context.Context, tx account.Transaction) error
	findCalled bool
	saveCalled bool
	savedTx    account.Transaction
}

func (s *stubTransactionRepository) FindTransaction(ctx context.Context, transactionID string) (*account.Transaction, error) {
	s.findCalled = true
	return s.findFn(ctx, transactionID)
}

func (s *stubTransactionRepository) SaveTransaction(ctx context.Context, tx account.Transaction) error {
	s.saveCalled = true
	s.savedTx = tx
	if s.saveFn != nil {
		return s.saveFn(ctx, tx)
	}
	return nil
}

func withdrawnPayload(t *testing.T, evt account.MoneyWithdrawn) []byte {
	t.Helper()
	body, err := json.Marshal(evt)
	if err != nil {
		t.Fatalf("marshal MoneyWithdrawn: %v", err)
	}
	return body
}

func TestCategorizeTransactionEventHandler_Handle_WithMerchantName_CategorizesAndSaves(t *testing.T) {
	tx := account.Transaction{
		TransactionID: "transaction-1",
		AccountID:     "account-1",
		Type:          account.TransactionTypeWithdrawal,
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
		MerchantName:  "Starbucks Gangnam",
	}
	categorizer := &stubCategorizer{fn: func(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
		if merchantName != "Starbucks Gangnam" || amount != 5500 {
			t.Fatalf("Categorize() called with (%q, %d), want (Starbucks Gangnam, 5500)", merchantName, amount)
		}
		return account.TransactionCategoryFood
	}}
	repo := &stubTransactionRepository{findFn: func(ctx context.Context, transactionID string) (*account.Transaction, error) {
		return &tx, nil
	}}
	handler := appevent.NewCategorizeTransactionEventHandler(categorizer, repo)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
		MerchantName:  "Starbucks Gangnam",
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !categorizer.called {
		t.Fatal("want categorizer.Categorize to be called")
	}
	if !repo.saveCalled {
		t.Fatal("want repo.SaveTransaction to be called")
	}
	if repo.savedTx.Category != account.TransactionCategoryFood {
		t.Fatalf("saved Category = %v, want FOOD", repo.savedTx.Category)
	}
}

func TestCategorizeTransactionEventHandler_Handle_WithoutMerchantName_SkipsEntirely(t *testing.T) {
	categorizer := &stubCategorizer{fn: func(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
		t.Fatal("Categorize must not be called")
		return ""
	}}
	repo := &stubTransactionRepository{findFn: func(ctx context.Context, transactionID string) (*account.Transaction, error) {
		t.Fatal("FindTransaction must not be called")
		return nil, nil
	}}
	handler := appevent.NewCategorizeTransactionEventHandler(categorizer, repo)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if categorizer.called {
		t.Fatal("want categorizer.Categorize NOT to be called")
	}
	if repo.findCalled {
		t.Fatal("want repo.FindTransaction NOT to be called")
	}
	if repo.saveCalled {
		t.Fatal("want repo.SaveTransaction NOT to be called")
	}
}

func TestCategorizeTransactionEventHandler_Handle_WhenTransactionNoLongerExists_SkipsWithoutError(t *testing.T) {
	categorizer := &stubCategorizer{fn: func(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
		t.Fatal("Categorize must not be called")
		return ""
	}}
	repo := &stubTransactionRepository{findFn: func(ctx context.Context, transactionID string) (*account.Transaction, error) {
		return nil, nil
	}}
	handler := appevent.NewCategorizeTransactionEventHandler(categorizer, repo)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
		MerchantName:  "Starbucks Gangnam",
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if categorizer.called {
		t.Fatal("want categorizer.Categorize NOT to be called")
	}
	if repo.saveCalled {
		t.Fatal("want repo.SaveTransaction NOT to be called")
	}
}

func TestCategorizeTransactionEventHandler_Handle_WhenFindFails_PropagatesErrorForRedelivery(t *testing.T) {
	repo := &stubTransactionRepository{findFn: func(ctx context.Context, transactionID string) (*account.Transaction, error) {
		return nil, errors.New("db unavailable")
	}}
	handler := appevent.NewCategorizeTransactionEventHandler(&stubCategorizer{fn: func(ctx context.Context, merchantName string, amount int64) account.TransactionCategory {
		t.Fatal("Categorize must not be called")
		return ""
	}}, repo)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
		MerchantName:  "Starbucks Gangnam",
	})
	if err := handler.Handle(context.Background(), payload); err == nil {
		t.Fatal("want error to propagate so the message is left unacked for redelivery")
	}
}
