package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.query.GetTransactionsResult
import com.example.accountservice.config.LlmProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.LocalDateTime

class NlTransactionAnswerComposerImplTest {
    private val httpClient = mockk<HttpClient>()
    private val objectMapper = jacksonObjectMapper()
    private lateinit var composer: NlTransactionAnswerComposerImpl

    private val transactions =
        listOf(
            GetTransactionsResult.TransactionSummary(
                transactionId = "t1",
                type = "DEPOSIT",
                amount = GetTransactionsResult.MoneyResult(1000, "KRW"),
                createdAt = LocalDateTime.of(2026, 7, 10, 0, 0),
            ),
        )

    @BeforeEach
    fun setUp() {
        composer = NlTransactionAnswerComposerImpl(LlmProperties("http://localhost:11434", "qwen2.5:1.5b"), objectMapper, httpClient)
    }

    private fun mockOllamaResponse(
        statusCode: Int,
        content: String?,
    ) {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns statusCode
        if (content != null) {
            val body = objectMapper.writeValueAsString(mapOf("message" to mapOf("content" to content)))
            every { response.body() } returns body
        }
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns response
    }

    @Test
    fun `returns the trimmed answer when the model answers`() {
        mockOllamaResponse(200, "  You deposited 1000 KRW.  ")

        val answer = composer.compose("How much did I deposit?", transactions)

        assertThat(answer).isEqualTo("You deposited 1000 KRW.")
    }

    @Test
    fun `falls back to a plain summary naming the actual count when the ollama call fails`() {
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws IOException("connection refused")

        val answer = composer.compose("How much did I deposit?", transactions)

        assertThat(answer).contains("Found 1 matching transaction(s)")
    }

    @Test
    fun `says so plainly when there are no transactions and the call fails`() {
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws IOException("connection refused")

        val answer = composer.compose("How much did I withdraw?", emptyList())

        assertThat(answer).isEqualTo("No matching transactions were found.")
    }
}
