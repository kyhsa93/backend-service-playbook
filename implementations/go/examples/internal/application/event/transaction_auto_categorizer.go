package event

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// TransactionAutoCategorizer is a Technical Service port (see root
// docs/architecture/domain-service.md) wrapping a self-hosted LLM call — the
// same self-hosted qwen2.5:1.5b Ollama setup as
// query.NlTransactionQueryTranslator/query.NlTransactionAnswerComposer, just
// classifying a merchant name + amount into a fixed spending category
// instead of translating a question or generating an answer. Defined here,
// in the Application layer, in the minimal form its consumer
// (CategorizeTransactionEventHandler) needs — the same idiom as
// query.NlTransactionQueryTranslator — with the real implementation (a
// self-hosted Ollama call) living under internal/infrastructure/llm.
//
// Categorize has no error return by contract, mirroring
// NlTransactionQueryTranslator.Translate: on any failure (network error,
// non-2xx response, malformed output, an out-of-taxonomy answer) the real
// implementation must log a warning and return
// account.TransactionCategoryOther rather than propagating an error — a
// classification failure is a best-effort-enrichment concern, never a
// financial-correctness one, so it must never block or fail this handler.
type TransactionAutoCategorizer interface {
	Categorize(ctx context.Context, merchantName string, amount int64) account.TransactionCategory
}
