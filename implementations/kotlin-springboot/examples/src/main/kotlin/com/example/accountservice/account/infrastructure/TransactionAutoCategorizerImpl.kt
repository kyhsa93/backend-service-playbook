package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.service.TransactionAutoCategorizer
import com.example.accountservice.account.domain.TransactionCategory
import com.example.accountservice.config.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same self-hosted `qwen2.5:1.5b` Ollama setup as [NlTransactionQueryTranslatorImpl]/
 * [NlTransactionAnswerComposerImpl], just a different prompt/schema for a different job
 * (classification instead of query translation or answer generation).
 */
@Component
class TransactionAutoCategorizerImpl(
    private val llmProperties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : TransactionAutoCategorizer {
    private val logger = LoggerFactory.getLogger(TransactionAutoCategorizerImpl::class.java)

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
        private val CATEGORIES = TransactionCategory.entries.map { it.name }

        // A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
        // technical-infrastructure concern, not a domain error — this is a best-effort enrichment,
        // not a financial correctness concern, so it degrades to OTHER rather than ever blocking or
        // retrying indefinitely. The same posture as NlTransactionQueryTranslatorImpl falling back to
        // no filter.
        private val FALLBACK_CATEGORY = TransactionCategory.OTHER
    }

    override fun categorize(
        merchantName: String,
        amount: Long,
    ): TransactionCategory {
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
                            OllamaMessage("user", "Merchant: $merchantName\nAmount: $amount"),
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
                logger.warn("Transaction categorization failed, using fallback category: status={}", response.statusCode())
                return FALLBACK_CATEGORY
            }

            val chatResponse = objectMapper.readValue(response.body(), OllamaChatResponse::class.java)
            val content = chatResponse.message?.content
            if (content.isNullOrBlank()) return FALLBACK_CATEGORY

            val parsed = objectMapper.readValue(content, ParsedCategory::class.java)
            return if (parsed.category != null && CATEGORIES.contains(parsed.category)) {
                TransactionCategory.valueOf(parsed.category)
            } else {
                FALLBACK_CATEGORY
            }
        } catch (e: Exception) {
            // A classification failure is a technical-infrastructure concern, not a domain error —
            // it must never block the withdrawal that already happened. Swallow it here at the
            // boundary.
            logger.warn("Transaction categorization failed, using fallback category: {}", e.message)
            return FALLBACK_CATEGORY
        }
    }

    private fun buildSystemPrompt(): String =
        "You classify a bank withdrawal into exactly one spending category based on its payee/merchant name and " +
            "amount. Categories: ${CATEGORIES.joinToString(", ")}. Use OTHER only when none of the other categories " +
            "plausibly fit. Respond only through the given schema."
}
