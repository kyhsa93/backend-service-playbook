package com.example.accountservice.payment.infrastructure

import com.example.accountservice.config.LlmProperties
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.RefundReasonCategory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same self-hosted `qwen2.5:1.5b` Ollama setup as
 * [com.example.accountservice.account.infrastructure.TransactionAutoCategorizerImpl], just a
 * different prompt/schema for a different job (classifying a refund's free-text reason instead of a
 * transaction's merchant name).
 */
@Component
class RefundReasonClassifierImpl(
    private val llmProperties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : RefundReasonClassifier {
    private val logger = LoggerFactory.getLogger(RefundReasonClassifierImpl::class.java)

    private data class OllamaMessage(
        val role: String,
        val content: String,
    )

    private data class OllamaChatRequest(
        val model: String,
        val stream: Boolean,
        val messages: List<OllamaMessage>,
        val format: Map<String, Any>,
    )

    private data class OllamaResponseMessage(
        val content: String? = null,
    )

    private data class OllamaChatResponse(
        val message: OllamaResponseMessage? = null,
    )

    private data class ParsedCategory(
        val category: String? = null,
    )

    companion object {
        private val CATEGORIES = RefundReasonCategory.entries.map { it.name }

        // A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
        // technical-infrastructure concern, not a domain error — this is a best-effort ops-analytics
        // enrichment, so it degrades to OTHER rather than ever blocking or retrying indefinitely. The
        // same posture as TransactionAutoCategorizerImpl falling back to OTHER.
        private val FALLBACK_CATEGORY = RefundReasonCategory.OTHER
    }

    override fun classify(reason: String): RefundReasonCategory {
        try {
            val responseFormat =
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "category" to mapOf("type" to "string", "enum" to CATEGORIES),
                        ),
                    "required" to listOf("category"),
                    "additionalProperties" to false,
                )

            val requestBody =
                OllamaChatRequest(
                    model = llmProperties.model,
                    stream = false,
                    messages =
                        listOf(
                            OllamaMessage("system", buildSystemPrompt()),
                            OllamaMessage("user", reason),
                        ),
                    format = responseFormat,
                )

            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${llmProperties.ollamaBaseUrl}/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() / 100 != 2) {
                logger.warn("Refund reason classification failed, using fallback category: status={}", response.statusCode())
                return FALLBACK_CATEGORY
            }

            val chatResponse = objectMapper.readValue(response.body(), OllamaChatResponse::class.java)
            val content = chatResponse.message?.content
            if (content.isNullOrBlank()) return FALLBACK_CATEGORY

            val parsed = objectMapper.readValue(content, ParsedCategory::class.java)
            return if (parsed.category != null && CATEGORIES.contains(parsed.category)) {
                RefundReasonCategory.valueOf(parsed.category)
            } else {
                FALLBACK_CATEGORY
            }
        } catch (e: Exception) {
            // A classification failure is a technical-infrastructure concern, not a domain error —
            // it must never block the refund request that already happened. Swallow it here at the
            // boundary.
            logger.warn("Refund reason classification failed, using fallback category: {}", e.message)
            return FALLBACK_CATEGORY
        }
    }

    private fun buildSystemPrompt(): String =
        "You classify a customer's stated refund reason into exactly one category, for internal reporting only " +
            "(this never affects whether the refund is approved). Categories: ${CATEGORIES.joinToString(", ")}. Use OTHER " +
            "only when none of the other categories plausibly fit. Respond only through the given schema."
}
