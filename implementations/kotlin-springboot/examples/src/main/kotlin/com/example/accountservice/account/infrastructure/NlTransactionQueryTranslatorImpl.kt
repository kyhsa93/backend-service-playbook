package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.service.NlTransactionQueryTranslator
import com.example.accountservice.account.application.service.TransactionFilter
import com.example.accountservice.account.domain.TransactionType
import com.example.accountservice.config.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the "Retrieve"-preparation half of a structured-data RAG pipeline
 * ([com.example.accountservice.account.application.service.NlTransactionAnswerComposer] is the
 * "Generate" half). Talks to Ollama's native `/api/chat` endpoint over plain HTTP via [HttpClient]
 * (Ollama has no official Kotlin/JVM SDK), the same self-hosted `qwen2.5:1.5b` setup used elsewhere
 * in this repo for LLM Technical Services.
 */
@Component
class NlTransactionQueryTranslatorImpl(
    private val llmProperties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : NlTransactionQueryTranslator {
    private val logger = LoggerFactory.getLogger(NlTransactionQueryTranslatorImpl::class.java)

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

    private data class ParsedFilter(
        val type: String? = null,
        val fromDate: String? = null,
        val toDate: String? = null,
    )

    companion object {
        private val TYPES = listOf("DEPOSIT", "WITHDRAWAL", "INTEREST")

        // An empty filter degrades gracefully to "no narrowing" — AskTransactionHistoryService
        // still runs AccountQuery.findTransactions with no type/date constraint, so a translation
        // failure never blocks the question from being answered against the requester's most
        // recent transactions.
        private val FALLBACK_FILTER = TransactionFilter()
    }

    override fun translate(question: String): TransactionFilter {
        try {
            val responseFormat =
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "type" to mapOf("type" to "string", "enum" to TYPES + "ANY"),
                            "fromDate" to mapOf("type" to "string"),
                            "toDate" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("type", "fromDate", "toDate"),
                    "additionalProperties" to false,
                )

            val requestBody =
                OllamaChatRequest(
                    model = llmProperties.model,
                    stream = false,
                    messages =
                        listOf(
                            OllamaMessage("system", buildSystemPrompt()),
                            OllamaMessage("user", question),
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
                logger.warn("Transaction query translation failed, using no filter: status={}", response.statusCode())
                return FALLBACK_FILTER
            }

            val chatResponse = objectMapper.readValue(response.body(), OllamaChatResponse::class.java)
            val content = chatResponse.message?.content
            if (content.isNullOrBlank()) return FALLBACK_FILTER

            val parsed = objectMapper.readValue(content, ParsedFilter::class.java)
            return TransactionFilter(
                type = if (parsed.type != null && TYPES.contains(parsed.type)) TransactionType.valueOf(parsed.type) else null,
                fromDate = parseIsoDateOrNull(parsed.fromDate),
                toDate = parseIsoDateOrNull(parsed.toDate),
            )
        } catch (e: Exception) {
            // A translation failure is a technical-infrastructure concern, not a domain error — it
            // must never block the question from being answered. Swallow it here at the boundary.
            logger.warn("Transaction query translation failed, using no filter: {}", e.message)
            return FALLBACK_FILTER
        }
    }

    private fun parseIsoDateOrNull(value: String?): LocalDate? {
        if (value.isNullOrBlank() || !value.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return null
        return try {
            LocalDate.parse(value)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSystemPrompt(): String {
        val today = LocalDate.now().toString()
        return "You translate a user's natural-language question about their own bank account transaction history into " +
            "a structured JSON filter. Today's date is $today. Resolve any relative date expression (\"this month\", " +
            "\"last week\") against that date.\n" +
            "Fields: \"type\" — DEPOSIT, WITHDRAWAL, INTEREST, or ANY if the question doesn't ask about a specific type. " +
            "\"fromDate\"/\"toDate\" — an ISO 8601 date (YYYY-MM-DD), or an empty string if the question implies no date " +
            "boundary on that side.\n" +
            "Only extract constraints the question actually states or clearly implies. Never invent a date range or " +
            "transaction type the question doesn't support. Respond only through the given schema."
    }
}
