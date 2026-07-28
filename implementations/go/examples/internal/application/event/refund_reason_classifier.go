package event

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

// RefundReasonClassifier is a Technical Service port (see root
// docs/architecture/domain-service.md) wrapping a self-hosted LLM call — the
// same self-hosted qwen2.5:1.5b Ollama setup as
// account/event.TransactionAutoCategorizer, just classifying a refund's
// free-text reason into a fixed category instead of a transaction's merchant
// name into a spending category. Defined here, in the Application layer, in
// the minimal form its consumer (ClassifyRefundReasonEventHandler) needs —
// the same idiom as TransactionAutoCategorizer — with the real
// implementation (a self-hosted Ollama call) living under
// internal/infrastructure/llm.
//
// Classify has no error return by contract, mirroring
// TransactionAutoCategorizer.Categorize: on any failure (network error,
// non-2xx response, malformed output, an out-of-taxonomy answer) the real
// implementation must log a warning and return
// payment.RefundReasonCategoryOther rather than propagating an error — a
// classification failure is a best-effort-enrichment concern, never one
// that can affect RefundEligibilityService's judgment, so it must never
// block or fail this handler.
type RefundReasonClassifier interface {
	Classify(ctx context.Context, reason string) payment.RefundReasonCategory
}
