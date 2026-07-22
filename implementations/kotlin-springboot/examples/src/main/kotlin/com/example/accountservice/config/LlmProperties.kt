package com.example.accountservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Non-sensitive default settings for the refund-reason classifier (a Technical Service, see
 * payment/infrastructure/RefundReasonClassifierImpl.kt). [ollamaBaseUrl] points at a self-hosted
 * Ollama instance (docker-compose.yml's `ollama`/`ollama-init` services) — unlike the Claude API this
 * replaced, there's no API key to guard: Ollama is self-hosted and the base URL is a plain,
 * non-sensitive config value, so no Secrets Manager lookup is needed here at all. Neither field is
 * `@field:NotBlank`: both already have sane defaults, and a missing/blank value must never fail
 * application startup — a classification outage is tolerated at runtime as a non-blocking fallback,
 * not a fail-fast condition.
 */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val ollamaBaseUrl: String = "http://localhost:11434",
    val model: String = "qwen2.5:1.5b",
)
