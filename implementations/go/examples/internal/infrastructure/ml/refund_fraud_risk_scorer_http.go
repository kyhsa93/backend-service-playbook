package ml

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"

	"github.com/example/account-service/internal/domain/payment"
)

// fallbackScore is used whenever the score can't be trusted (the shared
// scorer unreachable, malformed output). A neutral 0 never blocks the refund
// flow on its own — payment.EvaluateRefundEligibility's other checks still
// run against it, the same fallback stance as RefundReasonClassifierImpl's.
const fallbackScore = 0.0

type scoreRequest struct {
	RefundCountLast30Days         int     `json:"refundCountLast30Days"`
	RejectedRefundCountLast30Days int     `json:"rejectedRefundCountLast30Days"`
	RefundToPaymentAmountRatio    float64 `json:"refundToPaymentAmountRatio"`
	MinutesSincePayment           float64 `json:"minutesSincePayment"`
}

type scoreResponse struct {
	RiskScore *float64 `json:"riskScore"`
}

// RefundFraudRiskScorerHTTPImpl is a Technical Service wrapping the shared
// services/fraud-risk-scorer microservice (Python + scikit-learn, trained on
// the same synthetic dataset as refund_fraud_risk_scorer_native.go's
// hand-rolled model). Every one of the 5 language implementations calls this
// same service over plain HTTP — the "one shared model" side of the pair;
// see config.FraudScorerMode for how FRAUD_SCORER_MODE selects this impl
// over the in-process native one.
type RefundFraudRiskScorerHTTPImpl struct {
	httpClient *http.Client
	baseURL    string
}

// NewRefundFraudRiskScorerHTTPImpl builds the scorer from an already-resolved base URL — see
// config.FraudScorerBaseURL.
func NewRefundFraudRiskScorerHTTPImpl(baseURL string) *RefundFraudRiskScorerHTTPImpl {
	return &RefundFraudRiskScorerHTTPImpl{httpClient: &http.Client{}, baseURL: baseURL}
}

// Score never returns an error: on any failure (network error, non-2xx
// response, malformed output) it logs a warning and returns fallbackScore
// instead of propagating an error, so a scorer-service/network outage never
// blocks a refund request (RequestRefundHandler always gets a usable score
// to pass to payment.EvaluateRefundEligibility).
func (s *RefundFraudRiskScorerHTTPImpl) Score(ctx context.Context, features payment.RefundRiskFeatures) float64 {
	body, err := json.Marshal(scoreRequest{
		RefundCountLast30Days:         features.RefundCountLast30Days,
		RejectedRefundCountLast30Days: features.RejectedRefundCountLast30Days,
		RefundToPaymentAmountRatio:    features.RefundToPaymentAmountRatio,
		MinutesSincePayment:           features.MinutesSincePayment,
	})
	if err != nil {
		slog.Warn("fraud risk scoring request could not be built, using fallback", "error", err)
		return fallbackScore
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.baseURL+"/score", bytes.NewReader(body))
	if err != nil {
		slog.Warn("fraud risk scoring request could not be built, using fallback", "error", err)
		return fallbackScore
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		slog.Warn("fraud risk scoring failed, using fallback", "error", err)
		return fallbackScore
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		slog.Warn("fraud risk scoring failed, using fallback", "status", resp.StatusCode)
		return fallbackScore
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		slog.Warn("fraud risk scoring response could not be read, using fallback", "error", err)
		return fallbackScore
	}

	var parsed scoreResponse
	if err := json.Unmarshal(respBody, &parsed); err != nil {
		slog.Warn("fraud risk scoring returned malformed output, using fallback", "error", err)
		return fallbackScore
	}
	if parsed.RiskScore == nil {
		slog.Warn("fraud risk scoring returned no riskScore field, using fallback")
		return fallbackScore
	}

	return clampUnit(*parsed.RiskScore)
}

func clampUnit(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}
