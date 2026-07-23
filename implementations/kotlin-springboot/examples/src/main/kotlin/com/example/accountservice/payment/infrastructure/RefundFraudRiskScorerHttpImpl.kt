package com.example.accountservice.payment.infrastructure

import com.example.accountservice.config.FraudScorerProperties
import com.example.accountservice.payment.application.service.RefundFraudRiskScorer
import com.example.accountservice.payment.domain.RefundRiskFeatures
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Used whenever the score can't be trusted (the shared scorer unreachable, non-2xx, malformed
// output). A neutral 0 never blocks the refund flow on its own — RefundEligibilityService's other
// checks still run against it, the same fallback stance as RefundReasonClassifierImpl's.
private const val FALLBACK_SCORE = 0.0

private data class ScoreRequest(
    val refundCountLast30Days: Int,
    val rejectedRefundCountLast30Days: Int,
    val refundToPaymentAmountRatio: Double,
    val minutesSincePayment: Double,
)

private data class ScoreResponse(
    val riskScore: Double? = null,
)

/**
 * A Technical Service wrapping the shared `services/fraud-risk-scorer` microservice (Python +
 * scikit-learn, trained on the same synthetic dataset as [RefundFraudRiskScorerNativeImpl]'s
 * hand-rolled model). Every one of the 5 language implementations calls this same service over plain
 * HTTP — the "one shared model" side of the pair; see
 * [com.example.accountservice.config.FraudScorerProperties] for how `fraud-scorer.mode`
 * (`FRAUD_SCORER_MODE`) selects this impl over the in-process native one. Talks to the service over
 * plain HTTP via the JDK's own [HttpClient], the same choice [RefundReasonClassifierImpl] makes for
 * calling Ollama.
 */
@Component
@ConditionalOnProperty(prefix = "fraud-scorer", name = ["mode"], havingValue = "http")
class RefundFraudRiskScorerHttpImpl(
    private val fraudScorerProperties: FraudScorerProperties,
    private val objectMapper: ObjectMapper,
) : RefundFraudRiskScorer {
    private val logger = LoggerFactory.getLogger(RefundFraudRiskScorerHttpImpl::class.java)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    override fun score(features: RefundRiskFeatures): Double =
        try {
            val response = httpClient.send(buildRequest(features), HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                logger.warn("Fraud risk scoring failed, using fallback: HTTP {}", response.statusCode())
                FALLBACK_SCORE
            } else {
                parseScore(response.body()) ?: FALLBACK_SCORE
            }
        } catch (e: Exception) {
            // A scoring failure is a technical-infrastructure concern, not a domain error — it must
            // never block a refund request. Swallow it here at the boundary and fall back.
            logger.warn("Fraud risk scoring failed, using fallback: {}", e.message)
            FALLBACK_SCORE
        }

    private fun buildRequest(features: RefundRiskFeatures): HttpRequest {
        val requestBody =
            ScoreRequest(
                refundCountLast30Days = features.refundCountLast30Days,
                rejectedRefundCountLast30Days = features.rejectedRefundCountLast30Days,
                refundToPaymentAmountRatio = features.refundToPaymentAmountRatio,
                minutesSincePayment = features.minutesSincePayment,
            )

        return HttpRequest
            .newBuilder(URI.create("${fraudScorerProperties.baseUrl}/score"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build()
    }

    private fun parseScore(body: String): Double? {
        val riskScore = objectMapper.readValue(body, ScoreResponse::class.java).riskScore ?: return null
        return riskScore.coerceIn(0.0, 1.0)
    }
}
