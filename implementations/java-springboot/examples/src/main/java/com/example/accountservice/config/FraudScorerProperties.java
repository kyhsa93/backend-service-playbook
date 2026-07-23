package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mode} selects which {@code RefundFraudRiskScorer} implementation Spring wires up — {@code
 * native} (default, self-contained, no extra service — see {@code
 * payment/infrastructure/RefundFraudRiskScorerNativeImpl.java}'s {@code @ConditionalOnProperty}) or
 * {@code http} (calls the shared {@code services/fraud-risk-scorer} microservice — see {@code
 * payment/infrastructure/RefundFraudRiskScorerHttpImpl.java}'s). {@code baseUrl} points at that
 * microservice ({@code docker-compose.yml}'s {@code fraud-risk-scorer} service) and is only
 * actually read when {@code mode=http}. Like {@code RefundClassifierProperties}, neither value is a
 * secret, so it's a plain {@code @ConfigurationProperties} value with no Secrets Manager lookup.
 */
@ConfigurationProperties(prefix = "fraud-scorer")
@Validated
public record FraudScorerProperties(@NotBlank String mode, @NotBlank String baseUrl) {}
