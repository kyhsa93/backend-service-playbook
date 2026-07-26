package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ollamaBaseUrl} points at a self-hosted Ollama instance (see {@code
 * account/infrastructure/NlTransactionQueryTranslatorImpl.java}/{@code
 * NlTransactionAnswerComposerImpl.java} and {@code docker-compose.yml}'s {@code ollama}/{@code
 * ollama-init} services) — it isn't a secret, so it's a plain {@code @ConfigurationProperties}
 * value with no production/profile-gated Secrets Manager lookup (see secret-manager.md). Named
 * generically ({@code llm}, not tied to any one feature) since more than one Technical Service in
 * this codebase may call the same self-hosted model.
 */
@ConfigurationProperties(prefix = "llm")
@Validated
public record LlmProperties(@NotBlank String ollamaBaseUrl, @NotBlank String model) {}
