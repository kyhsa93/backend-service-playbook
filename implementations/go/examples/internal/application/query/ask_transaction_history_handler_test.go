package query_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
)

// stubAccountQuery records every call it receives so tests can assert not
// just the return value but exactly what arguments AskTransactionHistoryHandler
// passed down — the security-critical property under test is about call
// arguments (which ID scoped the lookup), not just the final answer.
type stubAccountQuery struct {
	acc                   *account.Account
	findTransactionsFn    func(q account.FindTransactionsQuery) ([]account.Transaction, int, error)
	findAccountsCalls     []account.FindQuery
	findTransactionsCalls []account.FindTransactionsQuery
}

func (s *stubAccountQuery) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	s.findAccountsCalls = append(s.findAccountsCalls, q)
	if s.acc == nil {
		return nil, 0, nil
	}
	return []*account.Account{s.acc}, 1, nil
}

func (s *stubAccountQuery) FindTransactions(ctx context.Context, q account.FindTransactionsQuery) ([]account.Transaction, int, error) {
	s.findTransactionsCalls = append(s.findTransactionsCalls, q)
	if s.findTransactionsFn != nil {
		return s.findTransactionsFn(q)
	}
	return nil, 0, nil
}

func (s *stubAccountQuery) HasTransactionWithReference(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error) {
	return false, nil
}

type stubTranslator struct {
	filter query.TransactionFilter
}

func (s stubTranslator) Translate(ctx context.Context, question string) query.TransactionFilter {
	return s.filter
}

type stubComposer struct {
	answer string
}

func (s stubComposer) Compose(ctx context.Context, question string, transactions []query.TransactionSummary) string {
	return s.answer
}

func TestAskTransactionHistoryHandler_Handle_AccountNotFound(t *testing.T) {
	repo := &stubAccountQuery{}
	handler := query.NewAskTransactionHistoryHandler(repo, stubTranslator{}, stubComposer{})

	_, err := handler.Handle(context.Background(), query.AskTransactionHistoryQuery{
		AccountID: "missing", RequesterID: "owner-1", Question: "anything",
	})

	if !errors.Is(err, account.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

// TestAskTransactionHistoryHandler_Handle_AlwaysScopesRetrievalToTheAuthenticatedRequester
// is the security-critical regression test described in
// ask_transaction_history_handler.go's doc comment: TransactionFilter
// carries only type/date fields returned by a mocked translator — no
// owner/account identifier at all — so no matter what the translator
// returns, the account looked up (and the transactions retrieved) must
// always be the one the authenticated requester (RequesterID) owns, never
// derived from the filter.
func TestAskTransactionHistoryHandler_Handle_AlwaysScopesRetrievalToTheAuthenticatedRequester(t *testing.T) {
	acc := account.New("owner-1", "owner-1@example.com", "KRW")
	repo := &stubAccountQuery{acc: acc}
	translator := stubTranslator{filter: query.TransactionFilter{
		Type: account.TransactionTypeWithdrawal, FromDate: "2026-07-01", ToDate: "2026-07-31",
	}}
	composer := stubComposer{answer: "you withdrew some money in July"}
	handler := query.NewAskTransactionHistoryHandler(repo, translator, composer)

	result, err := handler.Handle(context.Background(), query.AskTransactionHistoryQuery{
		AccountID: acc.AccountID, RequesterID: "owner-1", Question: "How much did I withdraw in July?",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(repo.findAccountsCalls) != 1 || repo.findAccountsCalls[0].OwnerID != "owner-1" {
		t.Fatalf("want FindAccounts called once with OwnerID owner-1, got %+v", repo.findAccountsCalls)
	}
	if len(repo.findTransactionsCalls) != 1 {
		t.Fatalf("want FindTransactions called exactly once, got %d", len(repo.findTransactionsCalls))
	}
	call := repo.findTransactionsCalls[0]
	if call.AccountID != acc.AccountID {
		t.Fatalf("FindTransactions AccountID = %q, want %q (the requester's own account, not anything derived from the filter)", call.AccountID, acc.AccountID)
	}
	// The translator's filter is allowed to narrow WHAT is retrieved.
	if call.Type != account.TransactionTypeWithdrawal || call.FromDate != "2026-07-01" || call.ToDate != "2026-07-31" {
		t.Fatalf("want the translated filter applied to the retrieval, got %+v", call)
	}
	if result.Answer != "you withdrew some money in July" {
		t.Fatalf("Answer = %q, want the composer's answer", result.Answer)
	}
}

func TestAskTransactionHistoryHandler_Handle_ReturnsMatchedCountFromRetrieval(t *testing.T) {
	acc := account.New("owner-1", "owner-1@example.com", "KRW")
	repo := &stubAccountQuery{
		acc: acc,
		findTransactionsFn: func(q account.FindTransactionsQuery) ([]account.Transaction, int, error) {
			return []account.Transaction{
				{TransactionID: "t1", Type: account.TransactionTypeDeposit, Amount: account.Money{Amount: 1000, Currency: "KRW"}},
			}, 1, nil
		},
	}
	handler := query.NewAskTransactionHistoryHandler(repo, stubTranslator{}, stubComposer{answer: "answer"})

	result, err := handler.Handle(context.Background(), query.AskTransactionHistoryQuery{
		AccountID: acc.AccountID, RequesterID: "owner-1", Question: "anything",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.MatchedCount != 1 {
		t.Fatalf("MatchedCount = %d, want 1", result.MatchedCount)
	}
}
