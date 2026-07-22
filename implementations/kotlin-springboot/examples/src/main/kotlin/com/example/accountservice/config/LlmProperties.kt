package com.example.accountservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Non-sensitive default settings for the refund-reason classifier (a Technical Service, see
 * payment/infrastructure/RefundReasonClassifierImpl.kt). [apiKey] is the raw ANTHROPIC_API_KEY value —
 * used directly only outside the "prod" Spring profile. In production the real key never comes from
 * here: RefundReasonClassifierImpl gates on `Profiles.of("prod")` and looks the key up from Secrets
 * Manager instead, the same convention `SecretsEnvironmentPostProcessor` uses for `jwt.secret` (see
 * docs/architecture/secret-manager.md). Unlike `JwtProperties.secret`, [apiKey] is intentionally not
 * `@field:NotBlank` — a missing/blank key must never fail application startup, since a classification
 * outage is tolerated at runtime as a non-blocking fallback, not a fail-fast condition.
 */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    val apiKey: String = "",
    val model: String = "claude-opus-4-8",
)
