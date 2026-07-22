from __future__ import annotations

import os

DEFAULT_REFUND_CLASSIFIER_MODEL = "qwen2.5:1.5b"
DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"


def get_refund_classifier_model() -> str:
    return os.getenv("REFUND_CLASSIFIER_MODEL", DEFAULT_REFUND_CLASSIFIER_MODEL)


# Ollama is self-hosted (see docker-compose.yml's ollama/ollama-init services) — there's no API
# key to guard, unlike the Claude API this replaced. The base URL is a plain, non-sensitive
# config value; inside Docker Compose it resolves via the service name (OLLAMA_BASE_URL is set
# to http://ollama:11434 on the app service), and defaults to localhost for running outside
# Compose. All os.environ/os.getenv access must live in src/config/*_config.py — see config.md.
def get_ollama_base_url() -> str:
    return os.getenv("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL)
