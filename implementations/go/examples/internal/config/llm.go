package config

import "os"

const defaultRefundClassifierModel = "qwen2.5:1.5b"
const defaultOllamaBaseURL = "http://localhost:11434"

// RefundClassifierModel returns the model id RefundReasonClassifierImpl uses, overridable via
// REFUND_CLASSIFIER_MODEL. All raw env var access for this feature is encapsulated here (never
// read directly inside domain/application/infrastructure code — config.md).
func RefundClassifierModel() string {
	if v := os.Getenv("REFUND_CLASSIFIER_MODEL"); v != "" {
		return v
	}
	return defaultRefundClassifierModel
}

// OllamaBaseURL returns the base URL RefundReasonClassifierImpl talks to, overridable via
// OLLAMA_BASE_URL. Ollama is self-hosted (see docker-compose.yml's ollama/ollama-init
// services) — there's no API key to guard, unlike the Claude API this replaced. The base URL
// is a plain, non-sensitive config value, so no Secrets Manager branch is needed here (compare
// LoadJWTSecret in jwt.go); inside Docker Compose it resolves via the service name
// (OLLAMA_BASE_URL is set to http://ollama:11434 on the app service), and defaults to
// localhost for running outside Compose.
func OllamaBaseURL() string {
	if v := os.Getenv("OLLAMA_BASE_URL"); v != "" {
		return v
	}
	return defaultOllamaBaseURL
}
