package com.example.accountservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Non-sensitive default settings for refund fraud-risk scoring (a Technical Service, see
 * `payment/application/service/RefundFraudRiskScorer.kt`). [mode] selects which
 * [com.example.accountservice.payment.application.service.RefundFraudRiskScorer] implementation is
 * registered as the bean — `native` (the default; an in-process, hand-rolled logistic regression, see
 * `payment/infrastructure/RefundFraudRiskScorerNativeImpl.kt`) needs no extra service, `http` calls the
 * shared `services/fraud-risk-scorer` microservice (see
 * `payment/infrastructure/RefundFraudRiskScorerHttpImpl.kt`) via [baseUrl]. As with [LlmProperties],
 * neither field is `@field:NotBlank`: both already have sane defaults, and a missing/blank value must
 * never fail application startup — a scoring outage is tolerated at runtime as a non-blocking
 * fallback, not a fail-fast condition.
 */
@ConfigurationProperties(prefix = "fraud-scorer")
data class FraudScorerProperties(
    val mode: String = "native",
    val baseUrl: String = "http://localhost:8000",
) {
    val isHttpMode: Boolean get() = mode == "http"
}
