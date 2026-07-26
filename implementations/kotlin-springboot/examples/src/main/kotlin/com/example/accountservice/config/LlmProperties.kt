package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * `ollamaBaseUrl` points at a self-hosted Ollama instance (see
 * account/infrastructure/NlTransactionQueryTranslatorImpl.kt/NlTransactionAnswerComposerImpl.kt and
 * docker-compose.yml's ollama/ollama-init services) — it isn't a secret, so it's a plain
 * `@ConfigurationProperties` value with no production/profile-gated Secrets Manager lookup (see
 * secret-manager.md). Named generically (`llm`, not tied to any one feature) since more than one
 * Technical Service in this codebase may call the same self-hosted model.
 */
@Validated
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    @field:NotBlank
    val ollamaBaseUrl: String,
    @field:NotBlank
    val model: String,
)
