package event_test

import (
	"context"
	"errors"
	"testing"

	appevent "github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/domain/account"
)

type stubAnomalyQuery struct {
	findRecentWithdrawalAmountsFn func(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error)
	calls                         []struct {
		accountID, excludeTransactionID string
		limit                           int
	}
}

func (s *stubAnomalyQuery) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	return nil, 0, nil
}

func (s *stubAnomalyQuery) FindTransactions(ctx context.Context, q account.FindTransactionsQuery) ([]account.Transaction, int, error) {
	return nil, 0, nil
}

func (s *stubAnomalyQuery) HasTransactionWithReference(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
	return false, nil
}

func (s *stubAnomalyQuery) SummarizeTransactions(ctx context.Context, q account.SummarizeTransactionsQuery) (account.TransactionSummary, error) {
	return account.TransactionSummary{}, nil
}

func (s *stubAnomalyQuery) FindRecentWithdrawalAmounts(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error) {
	s.calls = append(s.calls, struct {
		accountID, excludeTransactionID string
		limit                           int
	}{accountID, excludeTransactionID, limit})
	if s.findRecentWithdrawalAmountsFn != nil {
		return s.findRecentWithdrawalAmountsFn(ctx, accountID, excludeTransactionID, limit)
	}
	return nil, nil
}

type stubAnomalyNotifier struct {
	called                         bool
	accountID, recipient, currency string
	amount                         int64
	notifyWithdrawalAnomalyFn      func(ctx context.Context, accountID, recipient string, amount int64, currency string) error
}

func (s *stubAnomalyNotifier) NotifyWithdrawalAnomaly(ctx context.Context, accountID, recipient string, amount int64, currency string) error {
	s.called = true
	s.accountID, s.recipient, s.amount, s.currency = accountID, recipient, amount, currency
	if s.notifyWithdrawalAnomalyFn != nil {
		return s.notifyWithdrawalAnomalyFn(ctx, accountID, recipient, amount, currency)
	}
	return nil
}

func TestDetectWithdrawalAnomalyEventHandler_Handle_WhenAmountIsAStatisticalOutlier_SendsAnAlertEmail(t *testing.T) {
	repo := &stubAnomalyQuery{findRecentWithdrawalAmountsFn: func(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error) {
		return []int64{10000, 12000, 9000, 11000, 10500, 9500}, nil
	}}
	notifier := &stubAnomalyNotifier{}
	handler := appevent.NewDetectWithdrawalAnomalyEventHandler(repo, notifier)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		AccountID:     "account-1",
		Email:         "owner@example.com",
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5000000, Currency: "KRW"},
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(repo.calls) != 1 {
		t.Fatalf("want FindRecentWithdrawalAmounts called once, got %d", len(repo.calls))
	}
	if repo.calls[0].accountID != "account-1" || repo.calls[0].excludeTransactionID != "transaction-1" || repo.calls[0].limit != 30 {
		t.Fatalf("FindRecentWithdrawalAmounts called with unexpected args: %+v", repo.calls[0])
	}
	if !notifier.called {
		t.Fatal("want NotifyWithdrawalAnomaly to be called")
	}
	if notifier.accountID != "account-1" || notifier.recipient != "owner@example.com" || notifier.amount != 5000000 || notifier.currency != "KRW" {
		t.Fatalf("NotifyWithdrawalAnomaly called with unexpected args: accountID=%s recipient=%s amount=%d currency=%s",
			notifier.accountID, notifier.recipient, notifier.amount, notifier.currency)
	}
}

func TestDetectWithdrawalAnomalyEventHandler_Handle_WhenAmountIsWithinTheNormalRange_SendsNoAlert(t *testing.T) {
	repo := &stubAnomalyQuery{findRecentWithdrawalAmountsFn: func(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error) {
		return []int64{4900000, 5100000, 4950000, 5050000, 5000000}, nil
	}}
	notifier := &stubAnomalyNotifier{}
	handler := appevent.NewDetectWithdrawalAnomalyEventHandler(repo, notifier)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		AccountID:     "account-1",
		Email:         "owner@example.com",
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5000000, Currency: "KRW"},
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if notifier.called {
		t.Fatal("want NotifyWithdrawalAnomaly NOT to be called")
	}
}

func TestDetectWithdrawalAnomalyEventHandler_Handle_WhenFewerThan5PriorWithdrawals_SendsNoAlertRegardlessOfAmount(t *testing.T) {
	repo := &stubAnomalyQuery{findRecentWithdrawalAmountsFn: func(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error) {
		return []int64{10000, 12000}, nil
	}}
	notifier := &stubAnomalyNotifier{}
	handler := appevent.NewDetectWithdrawalAnomalyEventHandler(repo, notifier)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		AccountID:     "account-1",
		Email:         "owner@example.com",
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5000000, Currency: "KRW"},
	})
	if err := handler.Handle(context.Background(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if notifier.called {
		t.Fatal("want NotifyWithdrawalAnomaly NOT to be called")
	}
}

func TestDetectWithdrawalAnomalyEventHandler_Handle_WhenFindRecentWithdrawalAmountsFails_PropagatesErrorForRedelivery(t *testing.T) {
	repo := &stubAnomalyQuery{findRecentWithdrawalAmountsFn: func(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error) {
		return nil, errors.New("db unavailable")
	}}
	notifier := &stubAnomalyNotifier{}
	handler := appevent.NewDetectWithdrawalAnomalyEventHandler(repo, notifier)

	payload := withdrawnPayload(t, account.MoneyWithdrawn{
		AccountID:     "account-1",
		Email:         "owner@example.com",
		TransactionID: "transaction-1",
		Amount:        account.Money{Amount: 5000000, Currency: "KRW"},
	})
	if err := handler.Handle(context.Background(), payload); err == nil {
		t.Fatal("want error to propagate so the message is left unacked for redelivery")
	}
	if notifier.called {
		t.Fatal("want NotifyWithdrawalAnomaly NOT to be called")
	}
}
