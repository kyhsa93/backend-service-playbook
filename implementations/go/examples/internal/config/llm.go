package config

import "os"

const defaultLLMModel = "qwen2.5:1.5b"
const defaultOllamaBaseURL = "http://localhost:11434"

// LLMModel returns the model id this service's Ollama-backed Technical
// Services (internal/infrastructure/llm — NlTransactionQueryTranslatorImpl/
// NlTransactionAnswerComposerImpl, see docs/architecture/domain-service.md's
// structured-data RAG example) use, overridable via LLM_MODEL. All raw env
// var access for this feature is encapsulated here (never read directly
// inside domain/application/infrastructure code — config.md).
func LLMModel() string {
	if v := os.Getenv("LLM_MODEL"); v != "" {
		return v
	}
	return defaultLLMModel
}

// OllamaBaseURL returns the base URL those Technical Services talk to,
// overridable via OLLAMA_BASE_URL. Ollama is self-hosted (see
// docker-compose.yml's ollama/ollama-init services) — there's no API key to
// guard. The base URL is a plain, non-sensitive config value, so no Secrets
// Manager branch is needed here (compare LoadJWTSecret in jwt.go); inside
// Docker Compose it resolves via the service name (OLLAMA_BASE_URL is set
// to http://ollama:11434 on the app service), and defaults to localhost for
// running outside Compose.
func OllamaBaseURL() string {
	if v := os.Getenv("OLLAMA_BASE_URL"); v != "" {
		return v
	}
	return defaultOllamaBaseURL
}
