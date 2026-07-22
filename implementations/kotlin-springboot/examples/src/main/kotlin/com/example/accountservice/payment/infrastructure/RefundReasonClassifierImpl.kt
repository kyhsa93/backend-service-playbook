package com.example.accountservice.payment.infrastructure

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.example.accountservice.config.LlmProperties
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.RefundReasonCategory
import com.example.accountservice.payment.domain.RefundReasonClassification
import com.example.accountservice.secret.application.service.SecretService
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

// The shape Claude must respond with — a separate, Jackson-annotated type kept in infrastructure/,
// not the plain domain/RefundReasonClassification.kt value. The domain type stays free of any
// framework/serialization annotation; this response type exists only to drive the LLM's structured
// output and is mapped into the domain value below.
private data class RefundReasonClassificationResponse(
    @JsonPropertyDescription("The category that best matches the refund reason's free text.")
    val category: RefundReasonCategory,
    @JsonPropertyDescription(
        "Fraud risk between 0.0 and 1.0. Base this purely on linguistic signals in the reason text " +
            "itself (vagueness, internal inconsistency, urgency/pressure language, or an admission " +
            "unrelated to the product) - never infer a high score just because the category is " +
            "FRAUD_SUSPECTED, and never infer a low score just because it isn't.",
    )
    val fraudRiskScore: Double,
)

// Used whenever the classification can't be trusted (API error, refusal, malformed output, network
// error). A neutral 'OTHER'/no-fraud-signal result never blocks the refund flow on its own —
// RefundEligibilityService's other checks still run against it.
private val FALLBACK_CLASSIFICATION = RefundReasonClassification(category = RefundReasonCategory.OTHER, fraudRiskScore = 0.0)

private const val SYSTEM_PROMPT =
    "You classify a customer refund request's free-text reason. Respond only through the given schema."

/**
 * The real implementation of [RefundReasonClassifier] (a Technical Service) — calls the Claude API
 * with a structured-output (JSON-schema-constrained) response, and falls back to
 * [FALLBACK_CLASSIFICATION] on ANY failure (API error, refusal, malformed output, network error). A
 * classification outage must never block a refund request, so the failure is swallowed at this
 * Infrastructure boundary rather than surfaced as a domain error (see
 * `docs/architecture/domain-service.md`).
 */
@Component
class RefundReasonClassifierImpl(
    private val secretService: SecretService,
    private val environment: Environment,
    private val llmProperties: LlmProperties,
) : RefundReasonClassifier {
    private val logger = LoggerFactory.getLogger(RefundReasonClassifierImpl::class.java)

    // The API key is looked up from Secrets Manager only under the "prod" Spring profile — every
    // other profile (local/test) uses ANTHROPIC_API_KEY directly via LlmProperties, with no network
    // call. Same profile-gating convention as SecretsEnvironmentPostProcessor's jwt.secret handling
    // (docs/architecture/secret-manager.md) — this gates on Profiles.of("prod"), not an env-var
    // value. Resolved lazily on first use (not at application bootstrap): unlike jwt.secret, a
    // missing/invalid key must never block application startup — see the class-level fallback above.
    private val client: AnthropicClient by lazy {
        val apiKey =
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                val json = secretService.getSecret("app/anthropic")
                jacksonObjectMapper().readTree(json).get("apiKey").asText()
            } else {
                llmProperties.apiKey
            }
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    }

    override fun classify(reason: String): RefundReasonClassification =
        try {
            val params =
                MessageCreateParams
                    .builder()
                    .model(llmProperties.model)
                    .maxTokens(256L)
                    .system(SYSTEM_PROMPT)
                    .outputConfig(RefundReasonClassificationResponse::class.java)
                    .addUserMessage(reason)
                    .build()

            val message = client.messages().create(params)

            val parsed =
                message
                    .content()
                    .stream()
                    .flatMap { it.text().stream() }
                    .findFirst()
                    .map { it.text() }
                    .orElse(null)

            if (parsed == null) {
                FALLBACK_CLASSIFICATION
            } else {
                RefundReasonClassification(
                    category = parsed.category,
                    fraudRiskScore = parsed.fraudRiskScore.coerceIn(0.0, 1.0),
                )
            }
        } catch (e: Exception) {
            // A classification failure is a technical-infrastructure concern, not a domain error — it
            // must never block a refund request. Swallow it here at the boundary and fall back.
            logger.warn("Refund reason classification failed, using fallback: {}", e.message)
            FALLBACK_CLASSIFICATION
        }
}
