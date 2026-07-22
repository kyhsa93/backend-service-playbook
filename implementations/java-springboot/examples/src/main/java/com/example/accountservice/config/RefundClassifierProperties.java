package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code anthropicApiKey} is only ever consumed outside the production profile (see {@code
 * payment/infrastructure/RefundReasonClassifierImpl.java}) — in production the key is looked up
 * from Secrets Manager instead, gated the same way as {@code JwtProperties.secret} (a Spring
 * profile check, not an environment variable), so its default here is a non-blank local-dev
 * placeholder rather than a value anything in production actually reads.
 */
@ConfigurationProperties(prefix = "refund-classifier")
@Validated
public record RefundClassifierProperties(
        @NotBlank String anthropicApiKey, @NotBlank String model) {}
