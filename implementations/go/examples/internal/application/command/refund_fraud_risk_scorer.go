package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

// RefundFraudRiskScorer is a Technical Service port (domain-service.md)
// abstracting an ML model that scores a refund's *history pattern* (refund
// frequency, refund/payment amount ratio, time since payment — see
// payment.RefundRiskFeatures) — a second, independent signal alongside
// RefundReasonClassifier's free-text classification. Two concrete
// implementations exist side by side (infrastructure/ml's
// RefundFraudRiskScorerNativeImpl / RefundFraudRiskScorerHTTPImpl), selected
// by config.FraudScorerMode() at composition-root time (cmd/server/main.go)
// — the same "swap via config" role RefundReasonClassifier's Claude-API-to-
// Ollama swap demonstrated once already, but as a live toggle rather than a
// one-time migration.
//
// payment.EvaluateRefundEligibility (a Domain Service) never depends on this
// interface and never calls a model itself — it only ever receives the
// already-computed score as one more plain float64 input alongside
// Payment/Refund/RefundReasonClassification, and applies its own fixed
// threshold. This keeps the "model call = Infrastructure, judgment = Domain"
// boundary intact, the same boundary RefundReasonClassifier keeps.
//
// Score has no error return by contract, the same reason Classify doesn't:
// on any failure (the shared microservice unreachable, malformed output)
// the real implementation must log a warning and return a neutral fallback
// (0) rather than propagating an error — a scoring outage must never block
// a refund request.
type RefundFraudRiskScorer interface {
	Score(ctx context.Context, features payment.RefundRiskFeatures) float64
}
