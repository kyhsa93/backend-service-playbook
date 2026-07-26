const DEFAULT_LLM_MODEL = 'qwen2.5:1.5b'
const DEFAULT_OLLAMA_BASE_URL = 'http://localhost:11434'

export function getLlmModel(): string {
  return process.env.LLM_MODEL ?? DEFAULT_LLM_MODEL
}

// Ollama is self-hosted (see docker-compose.yml's ollama/ollama-init services) — there's no API
// key to guard. The base URL is a plain, non-sensitive config value; inside Docker Compose it
// resolves via the service name (OLLAMA_BASE_URL is set to http://ollama:11434 on the app
// service), and defaults to localhost for running outside Compose.
export function getOllamaBaseUrl(): string {
  return process.env.OLLAMA_BASE_URL ?? DEFAULT_OLLAMA_BASE_URL
}
