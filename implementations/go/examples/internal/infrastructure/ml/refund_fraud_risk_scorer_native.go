// Package ml holds the real implementations of
// command.RefundFraudRiskScorer — a native in-process logistic regression
// (this file) and an HTTP call to the shared services/fraud-risk-scorer
// microservice (refund_fraud_risk_scorer_http.go). Selected by
// config.FraudScorerMode() at composition-root time (cmd/server/main.go) —
// the same "swap via config" role internal/infrastructure/llm plays for
// RefundReasonClassifier (see docs/architecture/domain-service.md).
package ml

import (
	"context"
	"math"
	"math/rand"

	"github.com/example/account-service/internal/domain/payment"
)

const (
	trainingExampleCount = 300
	trainingSeed         = 42
	learningRate         = 0.5
	epochs               = 500
	featureCount         = 4
)

type trainingExample struct {
	features payment.RefundRiskFeatures
	label    float64
}

// generateTrainingData builds a synthetic seed dataset standing in for real
// historical fraud-review outcomes — this example has no real user base to
// draw labeled data from. The label follows an explicit ground-truth rule
// (frequent + high-ratio + fast-after-payment refunds are risky) purely so
// the model has a non-trivial pattern to fit; replace this with real labeled
// history in production. This same generation rule is mirrored in
// services/fraud-risk-scorer/model.py (Python) so both sides of the "shared
// service vs. native" pair are trained on equivalent data. A fixed-seed
// math/rand source is used so the generated dataset — and therefore the
// trained weights — is identical on every run.
func generateTrainingData() []trainingExample {
	rng := rand.New(rand.NewSource(trainingSeed))
	examples := make([]trainingExample, 0, trainingExampleCount)
	for i := 0; i < trainingExampleCount; i++ {
		refundCount := rng.Intn(8)
		rejectedCount := rng.Intn(4)
		ratio := rng.Float64()
		minutes := rng.Float64() * 43200
		riskScore := float64(refundCount)*0.15 +
			float64(rejectedCount)*0.3 +
			ratio*0.4 +
			math.Max(0, 1-minutes/1440)*0.3
		label := 0.0
		if riskScore > 1.1 {
			label = 1.0
		}
		examples = append(examples, trainingExample{
			features: payment.RefundRiskFeatures{
				RefundCountLast30Days:         refundCount,
				RejectedRefundCountLast30Days: rejectedCount,
				RefundToPaymentAmountRatio:    ratio,
				MinutesSincePayment:           minutes,
			},
			label: label,
		})
	}
	return examples
}

// toVector scales each raw feature into a roughly 0-1 range before it
// reaches the model — plain gradient descent converges far more slowly (and
// less reliably) on unscaled inputs this different in magnitude (a
// single-digit count next to a value in the thousands). Mirrored exactly in
// services/fraud-risk-scorer/model.py's to_vector.
func toVector(f payment.RefundRiskFeatures) [featureCount]float64 {
	return [featureCount]float64{
		float64(f.RefundCountLast30Days) / 10,
		float64(f.RejectedRefundCountLast30Days) / 5,
		f.RefundToPaymentAmountRatio,
		math.Min(1, f.MinutesSincePayment/1440),
	}
}

func sigmoid(z float64) float64 {
	return 1 / (1 + math.Exp(-z))
}

type logisticModel struct {
	weights [featureCount]float64
	bias    float64
}

// trainLogisticRegression runs batch gradient descent on plain logistic
// regression — no external ML library, deliberately simple/inspectable (root
// docs/architecture/domain-service.md's RefundFraudRiskScorer example). This
// is the "each language trains natively" side of the pair;
// RefundFraudRiskScorerHTTPImpl (the shared services/fraud-risk-scorer
// microservice) is the "one shared service" side.
func trainLogisticRegression(examples []trainingExample) logisticModel {
	var model logisticModel
	n := float64(len(examples))

	for epoch := 0; epoch < epochs; epoch++ {
		var weightGradients [featureCount]float64
		var biasGradient float64

		for _, example := range examples {
			vector := toVector(example.features)
			z := model.bias
			for i, x := range vector {
				z += x * model.weights[i]
			}
			prediction := sigmoid(z)
			errTerm := prediction - example.label
			for i, x := range vector {
				weightGradients[i] += errTerm * x
			}
			biasGradient += errTerm
		}

		for i := range model.weights {
			model.weights[i] -= (learningRate * weightGradients[i]) / n
		}
		model.bias -= (learningRate * biasGradient) / n
	}

	return model
}

// RefundFraudRiskScorerNativeImpl is the default implementation of
// command.RefundFraudRiskScorer — trains a small logistic regression
// in-process, once, at construction time (see
// NewRefundFraudRiskScorerNativeImpl) against the synthetic dataset above.
// Self-contained; no extra service needed. A real deployment would retrain
// periodically against actual refund history instead of training once from a
// fixed synthetic set at startup.
type RefundFraudRiskScorerNativeImpl struct {
	model logisticModel
}

// NewRefundFraudRiskScorerNativeImpl trains the model once, here, at
// construction — cmd/server/main.go builds exactly one of these and reuses
// it for the process lifetime (the same "train once, cache the weights"
// singleton role a package-level lazy constructor plays elsewhere in this
// repo, expressed here as a plain constructor since Go has no DI container
// to enforce singleton scope for us — the composition root just doesn't
// construct a second one).
func NewRefundFraudRiskScorerNativeImpl() *RefundFraudRiskScorerNativeImpl {
	return &RefundFraudRiskScorerNativeImpl{model: trainLogisticRegression(generateTrainingData())}
}

func (s *RefundFraudRiskScorerNativeImpl) Score(_ context.Context, features payment.RefundRiskFeatures) float64 {
	vector := toVector(features)
	z := s.model.bias
	for i, x := range vector {
		z += x * s.model.weights[i]
	}
	return sigmoid(z)
}
