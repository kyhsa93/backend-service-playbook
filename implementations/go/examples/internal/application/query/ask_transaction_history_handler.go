package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// maxTransactionsForAnswer bounds the number of most-relevant transactions
// retrieved to ground the generated answer in. A question about "this
// month" or "last week" is expected to narrow well below this via the
// translated date filter; this cap just bounds the worst case (e.g. an
// unfiltered "show me everything") so the composer's prompt stays a
// reasonable size.
const maxTransactionsForAnswer = 50

type AskTransactionHistoryQuery struct {
	AccountID   string
	RequesterID string
	Question    string
}

type AskTransactionHistoryResult struct {
	Answer       string
	MatchedCount int
}

// AskTransactionHistoryHandler orchestrates a structured-data RAG pipeline
// entirely in the Application layer (never in the Interface handler, which
// only wraps the HTTP request into this Query and dispatches it):
//  1. Retrieve-preparation — NlTransactionQueryTranslator (LLM) turns the
//     free-text question into a structured filter (type/date range).
//  2. Retrieve — account.Query's FindTransactions runs that filter, scoped
//     to the account (an ordinary lookup, no LLM involved).
//  3. Generate — NlTransactionAnswerComposer (LLM) answers the question,
//     grounded only in the retrieved records.
//
// Security-critical: the translated filter may only narrow WHAT is
// returned. WHO it belongs to is never taken from it — the account is
// always looked up first via account.FindOne(AccountID, RequesterID), the
// same ownership check GetTransactionsHandler uses, and TransactionFilter
// itself has no field that could carry an owner/account identifier at all.
// Worst case on a bad translation is an inaccurate answer about the
// requester's own data, never someone else's data or unauthorized access.
type AskTransactionHistoryHandler struct {
	repo       account.Query
	translator NlTransactionQueryTranslator
	composer   NlTransactionAnswerComposer
}

func NewAskTransactionHistoryHandler(repo account.Query, translator NlTransactionQueryTranslator, composer NlTransactionAnswerComposer) *AskTransactionHistoryHandler {
	return &AskTransactionHistoryHandler{repo: repo, translator: translator, composer: composer}
}

func (h *AskTransactionHistoryHandler) Handle(ctx context.Context, q AskTransactionHistoryQuery) (*AskTransactionHistoryResult, error) {
	if _, err := account.FindOne(ctx, h.repo, q.AccountID, q.RequesterID); err != nil {
		return nil, fmt.Errorf("ask transaction history: %w", err)
	}

	filter := h.translator.Translate(ctx, q.Question)

	transactions, count, err := h.repo.FindTransactions(ctx, account.FindTransactionsQuery{
		AccountID: q.AccountID,
		Type:      filter.Type,
		FromDate:  filter.FromDate,
		ToDate:    filter.ToDate,
		Page:      0,
		Take:      maxTransactionsForAnswer,
	})
	if err != nil {
		return nil, fmt.Errorf("ask transaction history: %w", err)
	}

	summaries := make([]TransactionSummary, len(transactions))
	for i, t := range transactions {
		summaries[i] = TransactionSummary{
			TransactionID: t.TransactionID,
			Type:          string(t.Type),
			Amount:        MoneyResult{Amount: t.Amount.Amount, Currency: t.Amount.Currency},
			CreatedAt:     t.CreatedAt,
		}
	}

	answer := h.composer.Compose(ctx, q.Question, summaries)
	return &AskTransactionHistoryResult{Answer: answer, MatchedCount: count}, nil
}
