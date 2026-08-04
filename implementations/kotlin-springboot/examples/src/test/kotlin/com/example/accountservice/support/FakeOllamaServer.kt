package com.example.accountservice.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress

/**
 * An in-process fake of the one Ollama endpoint every LLM Technical Service in this codebase calls:
 * `POST /api/chat`, non-streaming (see `account/infrastructure/NlTransactionQueryTranslatorImpl.kt`/
 * `NlTransactionAnswerComposerImpl.kt`/`TransactionAutoCategorizerImpl.kt` and
 * `payment/infrastructure/RefundReasonClassifierImpl.kt`). An e2e test starts one instance in its
 * companion object and points `llm.ollama-base-url` at [baseUrl] via `@DynamicPropertySource`, so
 * each service's real HTTP request → response-parse path runs in the e2e loop without a live model.
 *
 * Routing is stateless and purely content-based — the system message identifies WHICH service is
 * calling (each service builds a distinctive system prompt), and recognizable markers in the user
 * message pick WHAT deterministic content to answer with — so there is no per-test mutable state to
 * reset between tests. Built on the JDK's own `com.sun.net.httpserver.HttpServer` (a single POST
 * endpoint needs no extra test dependency).
 */
class FakeOllamaServer {
    companion object {
        /**
         * Makes the fake answer 500 whenever it appears anywhere in a request's user message. Tests
         * that need to cover an LLM Technical Service's graceful-fallback path embed it in the
         * natural input that reaches the prompt (question/merchantName/reason), so the forced outage
         * is scoped to exactly that request — every other request in the suite keeps getting
         * deterministic successful responses.
         */
        const val FORCE_LLM_FAILURE_MARKER = "force-llm-500"

        /**
         * Marks answers produced by the fake composer branch below — asserting on it proves the
         * `/transactions/ask` answer really came through the LLM request/parse path (the composer's
         * own fallback answer starts with "Found ... matching transaction(s)" instead).
         */
        const val FAKE_ANSWER_PREFIX = "FAKE-OLLAMA GROUNDED ANSWER:\n"
    }

    private val objectMapper = jacksonObjectMapper()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/chat") { exchange -> handleChat(exchange) }
            start()
        }

    /** The base URL to bind to `llm.ollama-base-url` — the services append `/api/chat` themselves. */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /**
     * Stop from `@AfterAll` — the JDK server's dispatcher thread is non-daemon, so leaving it
     * running would keep the Gradle test-worker JVM alive after the suite finishes.
     */
    fun stop() {
        server.stop(0)
    }

    private fun handleChat(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, "only POST /api/chat is supported")
                return
            }

            val request = objectMapper.readValue(exchange.requestBody.readBytes(), Map::class.java)
            val messages = (request["messages"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            val systemContent = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String ?: ""
            val userContent = messages.firstOrNull { it["role"] == "user" }?.get("content") as? String ?: ""

            if (userContent.contains(FORCE_LLM_FAILURE_MARKER)) {
                respond(exchange, 500, "forced failure for fallback-path coverage")
                return
            }

            val content = route(systemContent, userContent)
            if (content == null) {
                // An unrecognized system prompt means a new LLM Technical Service was added without
                // teaching this fake about it — fail loudly (the caller's fallback assertion will
                // surface it).
                respond(exchange, 500, "fake Ollama does not recognize this system prompt")
                return
            }

            exchange.responseHeaders.add("Content-Type", "application/json")
            respond(
                exchange,
                200,
                objectMapper.writeValueAsString(mapOf("message" to mapOf("role" to "assistant", "content" to content))),
            )
        } finally {
            exchange.close()
        }
    }

    /**
     * Picks the deterministic response content for one chat request. Each branch matches a
     * distinctive phrase from the real system prompt built by one Impl, and returns content in
     * exactly the shape that service parses (structured JSON for the translator/categorizer/
     * classifier, free-form prose for the composer). Returns null for an unrecognized system prompt.
     */
    private fun route(
        systemContent: String,
        userContent: String,
    ): String? {
        val loweredUser = userContent.lowercase()
        return when {
            // NlTransactionQueryTranslatorImpl — buildSystemPrompt.
            systemContent.contains("translate a user's natural-language question") ->
                if (loweredUser.contains("deposit")) {
                    """{"type":"DEPOSIT","fromDate":"","toDate":""}"""
                } else {
                    """{"type":"ANY","fromDate":"","toDate":""}"""
                }

            // NlTransactionAnswerComposerImpl — SYSTEM_PROMPT. Echo the grounding data back so the
            // caller can assert the retrieved transactions really reached the prompt (and
            // non-matching ones did not).
            systemContent.contains("answer a user's question about their own bank account transactions") ->
                FAKE_ANSWER_PREFIX + userContent.substringAfter("Transactions:\n", userContent)

            // TransactionAutoCategorizerImpl — buildSystemPrompt. User content is
            // "Merchant: <name>\nAmount: <n>".
            systemContent.contains("classify a bank withdrawal") ->
                if (userContent.contains("Starbucks")) {
                    """{"category":"FOOD"}"""
                } else {
                    """{"category":"OTHER"}"""
                }

            // RefundReasonClassifierImpl — buildSystemPrompt. User content is the refund's stated
            // reason verbatim.
            systemContent.contains("classify a customer's stated refund reason") ->
                when {
                    loweredUser.contains("arrived broken") -> """{"category":"DEFECTIVE_PRODUCT"}"""
                    loweredUser.contains("changed my mind") -> """{"category":"CHANGED_MIND"}"""
                    else -> """{"category":"OTHER"}"""
                }

            else -> null
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
