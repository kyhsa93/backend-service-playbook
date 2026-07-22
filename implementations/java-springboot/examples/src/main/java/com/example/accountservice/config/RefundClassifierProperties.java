package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ollamaBaseUrl} points at a self-hosted Ollama instance (see {@code
 * payment/infrastructure/RefundReasonClassifierImpl.java} and {@code docker-compose.yml}'s {@code
 * ollama}/{@code ollama-init} services) — unlike the Anthropic API key this replaced, it isn't a
 * secret, so it's a plain {@code @ConfigurationProperties} value with no production/profile-gated
 * Secrets Manager lookup.
 */
@ConfigurationProperties(prefix = "refund-classifier")
@Validated
public record RefundClassifierProperties(@NotBlank String ollamaBaseUrl, @NotBlank String model) {}
