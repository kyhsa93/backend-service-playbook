package query

import "context"

// NlTransactionAnswerComposer is a Technical Service port (see root
// docs/architecture/domain-service.md) generating a natural-language
// answer grounded in already-retrieved transaction records — the
// "Generate" step of a structured-data RAG pipeline
// (NlTransactionQueryTranslator is the "Retrieve"-preparation step). It
// never queries data itself; AskTransactionHistoryHandler retrieves the
// records first (scoped to the authenticated requester) and passes them in
// here as plain data, so this service can never widen what's visible
// beyond what was already fetched.
//
// Compose has no error return by contract, the same as
// NlTransactionQueryTranslator.Translate: on any failure the real
// implementation (internal/infrastructure/llm) must log a warning and fall
// back to a plain templated summary rather than propagating an error — a
// composition failure must never block the question from getting *an*
// answer, even a plain one.
type NlTransactionAnswerComposer interface {
	Compose(ctx context.Context, question string, transactions []TransactionSummary) string
}
