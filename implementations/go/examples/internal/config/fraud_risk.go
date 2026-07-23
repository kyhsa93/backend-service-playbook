package config

import "os"

const defaultFraudScorerMode = "native"
const defaultFraudScorerBaseURL = "http://localhost:8000"

// FraudScorerMode returns which command.RefundFraudRiskScorer implementation
// cmd/server/main.go should wire up, overridable via FRAUD_SCORER_MODE
// ("native", the default, or "http"). "native" (in-process, hand-rolled
// logistic regression — infrastructure/ml's RefundFraudRiskScorerNativeImpl)
// needs no extra service and is the default so the app runs standalone.
// "http" calls the shared services/fraud-risk-scorer microservice
// (infrastructure/ml's RefundFraudRiskScorerHTTPImpl) — opt in via
// FRAUD_SCORER_MODE=http once that service is running (docker-compose.yml's
// fraud-risk-scorer service). Both implementations satisfy the same
// command.RefundFraudRiskScorer interface, so switching is a one-line env
// change — the same point RefundReasonClassifier's Claude-API-to-Ollama swap
// demonstrated once already (see llm.go), but as a live toggle.
func FraudScorerMode() string {
	if v := os.Getenv("FRAUD_SCORER_MODE"); v == "http" {
		return v
	}
	return defaultFraudScorerMode
}

// FraudScorerBaseURL returns the base URL RefundFraudRiskScorerHTTPImpl talks
// to, overridable via FRAUD_SCORER_BASE_URL. As with OllamaBaseURL, this is a
// plain, non-sensitive config value (no Secrets Manager branch needed) —
// inside Docker Compose it resolves via the service name
// (FRAUD_SCORER_BASE_URL is set to http://fraud-risk-scorer:8000 on the app
// service), and defaults to localhost for running outside Compose.
func FraudScorerBaseURL() string {
	if v := os.Getenv("FRAUD_SCORER_BASE_URL"); v != "" {
		return v
	}
	return defaultFraudScorerBaseURL
}
