package query

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// TransactionFilter is a plain, narrow shape carrying only fields that can
// safely narrow WHAT AskTransactionHistoryHandler retrieves. Deliberately
// has no AccountID/OwnerID field: the Handler always scopes the lookup to
// the authenticated requester's own account (via account.FindOne, using
// AskTransactionHistoryQuery.RequesterID), and never lets a value derived
// from an LLM's interpretation of free text influence WHO the data belongs
// to.
type TransactionFilter struct {
	Type     account.TransactionType // empty = no type filter
	FromDate string                  // ISO 8601 date (YYYY-MM-DD), inclusive; empty = no lower bound
	ToDate   string                  // ISO 8601 date (YYYY-MM-DD), inclusive; empty = no upper bound
}

// NlTransactionQueryTranslator is a Technical Service port (see root
// docs/architecture/domain-service.md) translating a free-text question
// about an account's transaction history into a structured filter — the
// "Retrieve"-preparation step of a structured-data RAG pipeline
// (NlTransactionAnswerComposer is the "Generate" step; account.Query's
// FindTransactions itself is the "Retrieve" step in between). Defined here,
// in the Application layer, in the minimal form the consumer
// (AskTransactionHistoryHandler) needs — the same idiom as
// command.PasswordHasher/command.TokenIssuer — with the real
// implementation (a self-hosted Ollama call) living under
// internal/infrastructure/llm.
//
// Translate has no error return by contract: on any failure (network
// error, non-2xx response, malformed output, ...) the real implementation
// must log a warning and return an empty TransactionFilter{} (no
// narrowing) rather than propagating an error — a translation failure must
// never block the question from being answered.
type NlTransactionQueryTranslator interface {
	Translate(ctx context.Context, question string) TransactionFilter
}
