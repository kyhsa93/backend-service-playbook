package com.example.accountservice.payment.infrastructure

import com.example.accountservice.config.LlmProperties
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.RefundReasonCategory
import com.example.accountservice.payment.domain.RefundReasonClassification
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val CATEGORIES = RefundReasonCategory.entries.map { it.name }

// Used whenever the classification can't be trusted (Ollama unreachable, non-2xx, malformed output,
// unknown category). A neutral 'OTHER'/no-fraud-signal result never blocks the refund flow on its
// own — RefundEligibilityService's other checks still run against it.
private val FALLBACK_CLASSIFICATION = RefundReasonClassification(category = RefundReasonCategory.OTHER, fraudRiskScore = 0.0)

// Deliberately explicit and example-anchored — the classifier runs on a small, self-hosted model
// (qwen2.5:1.5b) that, tested live against this exact prompt shape, otherwise conflates ordinary
// billing complaints ("charged twice, refund the duplicate") with fraud_suspected. A calm,
// single-issue complaint is never fraud_suspected on its own; only report fraud when the text itself
// shows deception or denies placing the order.
private const val SYSTEM_PROMPT =
    "You classify a customer refund request's free-text reason into exactly one category and a " +
        "fraud-risk score from 0 to 1. Respond only through the given schema.\n" +
        "Categories: defective_product (item broken/damaged/malfunctioning), not_as_described (wrong item or " +
        "mismatched description), duplicate_charge (billed more than once for the same order), changed_mind (no " +
        "longer wants the item, no product issue), fraud_suspected (the customer explicitly states they never " +
        "placed the order, or the message itself shows deception/inconsistency), other.\n" +
        "A plain, calm complaint about being billed twice is duplicate_charge with a LOW fraud score near 0 — it " +
        "is not fraud_suspected. Only use fraud_suspected for signs of deception, not for an ordinary billing " +
        "complaint."

// Ollama's `format` field takes a raw JSON Schema and constrains decoding to match it (grammar-based —
// this guarantees syntactically valid JSON matching this shape regardless of model size; it does NOT
// guarantee the category/score judgment itself is reliable at small sizes, which is why the system
// prompt above is unusually explicit and example-anchored). The enum values are the domain
// [RefundReasonCategory]'s own names, not the lowercase words used in the prompt's prose above — the
// grammar-constrained decoder only ever emits one of these literal tokens, so the response maps
// straight back onto the domain enum with no separate string-translation step.
private val RESPONSE_FORMAT =
    mapOf(
        "type" to "object",
        "properties" to
            mapOf(
                "category" to mapOf("type" to "string", "enum" to CATEGORIES),
                "fraudRiskScore" to mapOf("type" to "number"),
            ),
        "required" to listOf("category", "fraudRiskScore"),
        "additionalProperties" to false,
    )

private data class OllamaChatResponse(
    val message: OllamaMessage? = null,
)

private data class OllamaMessage(
    val content: String? = null,
)

private data class ClassificationPayload(
    val category: String? = null,
    val fraudRiskScore: Double? = null,
)

/**
 * The real implementation of [RefundReasonClassifier] (a Technical Service) — calls a self-hosted,
 * open-source LLM served by Ollama (docker-compose.yml's `ollama`/`ollama-init` services) over its
 * native `/api/chat` endpoint, with a JSON-schema-constrained response, and falls back to
 * [FALLBACK_CLASSIFICATION] on ANY failure (network error, non-2xx response, malformed output, unknown
 * category). A classification outage must never block a refund request, so the failure is swallowed at
 * this Infrastructure boundary rather than surfaced as a domain error (see
 * `docs/architecture/domain-service.md`). Talks to Ollama over plain HTTP via the JDK's own
 * [HttpClient] rather than a vendor SDK, since Ollama has no official Java/Kotlin client.
 */
@Component
class RefundReasonClassifierImpl(
    private val llmProperties: LlmProperties,
    private val objectMapper: ObjectMapper,
) : RefundReasonClassifier {
    private val logger = LoggerFactory.getLogger(RefundReasonClassifierImpl::class.java)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    override fun classify(reason: String): RefundReasonClassification =
        try {
            val response = httpClient.send(buildRequest(reason), HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                logger.warn("Refund reason classification failed, using fallback: HTTP {}", response.statusCode())
                FALLBACK_CLASSIFICATION
            } else {
                parseClassification(response.body()) ?: FALLBACK_CLASSIFICATION
            }
        } catch (e: Exception) {
            // A classification failure is a technical-infrastructure concern, not a domain error — it
            // must never block a refund request. Swallow it here at the boundary and fall back.
            logger.warn("Refund reason classification failed, using fallback: {}", e.message)
            FALLBACK_CLASSIFICATION
        }

    private fun buildRequest(reason: String): HttpRequest {
        val requestBody =
            mapOf(
                "model" to llmProperties.model,
                "stream" to false,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user", "content" to reason),
                    ),
                "format" to RESPONSE_FORMAT,
            )

        return HttpRequest
            .newBuilder(URI.create("${llmProperties.ollamaBaseUrl}/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
            .build()
    }

    private fun parseClassification(body: String): RefundReasonClassification? {
        val content = objectMapper.readValue(body, OllamaChatResponse::class.java).message?.content ?: return null
        val parsed = objectMapper.readValue(content, ClassificationPayload::class.java)
        val category = parsed.category?.let { raw -> CATEGORIES.find { it == raw } } ?: return null

        return RefundReasonClassification(
            category = RefundReasonCategory.valueOf(category),
            fraudRiskScore = (parsed.fraudRiskScore ?: 0.0).coerceIn(0.0, 1.0),
        )
    }
}
