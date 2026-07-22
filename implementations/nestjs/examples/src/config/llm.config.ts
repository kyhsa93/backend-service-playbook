const DEFAULT_REFUND_CLASSIFIER_MODEL = 'qwen2.5:1.5b'
const DEFAULT_OLLAMA_BASE_URL = 'http://localhost:11434'

export function getRefundClassifierModel(): string {
  return process.env.REFUND_CLASSIFIER_MODEL ?? DEFAULT_REFUND_CLASSIFIER_MODEL
}

// Ollama is self-hosted (see docker-compose.yml's ollama/ollama-init services) — there's no API
// key to guard, unlike the Claude API this replaced. The base URL is a plain, non-sensitive
// config value; inside Docker Compose it resolves via the service name (OLLAMA_BASE_URL is set
// to http://ollama:11434 on the app service), and defaults to localhost for running outside Compose.
export function getOllamaBaseUrl(): string {
  return process.env.OLLAMA_BASE_URL ?? DEFAULT_OLLAMA_BASE_URL
}
