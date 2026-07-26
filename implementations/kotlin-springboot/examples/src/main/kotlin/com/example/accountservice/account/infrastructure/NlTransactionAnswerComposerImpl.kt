package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.query.GetTransactionsResult
import com.example.accountservice.account.application.service.NlTransactionAnswerComposer
import com.example.accountservice.config.LlmProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A Technical Service (see root docs/architecture/domain-service.md) generating a natural-language
 * answer from already-retrieved transaction records — the "Generate" half of a structured-data RAG
 * pipeline ([com.example.accountservice.account.application.service.NlTransactionQueryTranslator]
 * is the "Retrieve"-preparation half). Uses the same self-hosted Ollama setup, without a
 * JSON-schema-constrained response since the output here is free-form prose, not a structured value
 * the caller parses.
 */
@Component
class NlTransactionAnswerComposerImpl(
    private val llmProperties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : NlTransactionAnswerComposer {
    private val logger = LoggerFactory.getLogger(NlTransactionAnswerComposerImpl::class.java)

    private data class OllamaMessage(
        val role: String,
        val content: String,
    )

    private data class OllamaChatRequest(
        val model: String,
        val stream: Boolean,
        val messages: List<OllamaMessage>,
    )

    private data class OllamaResponseMessage(
        val content: String? = null,
    )

    private data class OllamaChatResponse(
        val message: OllamaResponseMessage? = null,
    )

    companion object {
        // Detect-and-match-the-question's-language is asked of the model explicitly, mirroring the
        // same live-tested prompt shape used elsewhere in this repo for a small self-hosted model
        // (qwen2.5:1.5b) — see nestjs's NlTransactionAnswerComposerImpl for the same wording and the
        // live-test note on its language-matching limitation.
        private const val SYSTEM_PROMPT =
            "You answer a user's question about their own bank account transactions using ONLY the " +
                "transaction data listed below — never mention or infer a transaction that isn't in that list. Concisely " +
                "(2-3 sentences). If the listed data doesn't contain enough information to answer (e.g. it's empty), say so " +
                "plainly instead of guessing.\n" +
                "IMPORTANT: detect the language the question itself is written in, and write your entire answer in that same " +
                "language — e.g. a Korean question always gets a Korean answer, an English question always gets an English " +
                "answer, regardless of what language this instruction or the transaction data is in."
    }

    override fun compose(
        question: String,
        transactions: List<GetTransactionsResult.TransactionSummary>,
    ): String {
        try {
            val requestBody =
                OllamaChatRequest(
                    model = llmProperties.model,
                    stream = false,
                    messages =
                        listOf(
                            OllamaMessage("system", SYSTEM_PROMPT),
                            OllamaMessage("user", "Question: $question\n\nTransactions:\n${formatTransactions(transactions)}"),
                        ),
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
                logger.warn("Answer composition failed, using fallback: status={}", response.statusCode())
                return fallbackAnswer(transactions)
            }

            val chatResponse = objectMapper.readValue(response.body(), OllamaChatResponse::class.java)
            val content = chatResponse.message?.content
            return if (!content.isNullOrBlank()) content.trim() else fallbackAnswer(transactions)
        } catch (e: Exception) {
            // A composition failure is a technical-infrastructure concern, not a domain error — it
            // must never block the question from getting *an* answer, even a plain one.
            logger.warn("Answer composition failed, using fallback: {}", e.message)
            return fallbackAnswer(transactions)
        }
    }

    private fun formatTransactions(transactions: List<GetTransactionsResult.TransactionSummary>): String {
        if (transactions.isEmpty()) return "(no matching transactions)"
        return transactions.joinToString("\n") {
            "- ${it.type} ${it.amount.amount} ${it.amount.currency} on ${it.createdAt.toLocalDate()}"
        }
    }

    // A plain, non-blocking fallback used whenever the LLM call fails — describes the same data a
    // working call would have been grounded in, just without natural-language phrasing.
    private fun fallbackAnswer(transactions: List<GetTransactionsResult.TransactionSummary>): String {
        if (transactions.isEmpty()) return "No matching transactions were found."
        return "Found ${transactions.size} matching transaction(s):\n${formatTransactions(transactions)}"
    }
}
