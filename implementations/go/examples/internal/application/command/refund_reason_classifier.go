package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

// RefundReasonClassifier is a Technical Service port (domain-service.md)
// abstracting an LLM call that classifies a refund's free-text reason —
// the same pattern as PasswordHasher/TokenIssuer: defined in the Application
// layer in the minimal form the consumer (RequestRefundHandler) needs, with
// the real implementation (a self-hosted Ollama call) living in
// infrastructure.
//
// payment.EvaluateRefundEligibility (a Domain Service) never depends on this
// interface and never calls an LLM itself — it only ever receives the
// already-computed payment.RefundReasonClassification as a plain value and
// makes its own pure judgment from its fields. This keeps the "LLM call =
// Infrastructure, judgment = Domain" boundary intact: the classifier can
// only ever supply one more signal to weigh, never decide the outcome
// itself.
//
// Classify has no error return by contract: on any failure (API error,
// malformed output, refusal, network error) the real implementation must
// log a warning and return a neutral fallback classification rather than
// propagating an error — a classification outage must never block a refund
// request.
type RefundReasonClassifier interface {
	Classify(ctx context.Context, reason string) payment.RefundReasonClassification
}
