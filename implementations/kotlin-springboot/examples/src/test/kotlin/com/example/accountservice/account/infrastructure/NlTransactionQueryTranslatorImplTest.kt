package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.service.TransactionFilter
import com.example.accountservice.account.domain.TransactionType
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
import java.time.LocalDate

class NlTransactionQueryTranslatorImplTest {
    private val httpClient = mockk<HttpClient>()
    private val objectMapper = jacksonObjectMapper()
    private lateinit var translator: NlTransactionQueryTranslatorImpl

    @BeforeEach
    fun setUp() {
        translator = NlTransactionQueryTranslatorImpl(LlmProperties("http://localhost:11434", "qwen2.5:1.5b"), objectMapper, httpClient)
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
    fun `returns the model's type and dates as the filter when the response is valid`() {
        mockOllamaResponse(
            200,
            objectMapper.writeValueAsString(mapOf("type" to "WITHDRAWAL", "fromDate" to "2026-07-01", "toDate" to "2026-07-31")),
        )

        val filter = translator.translate("How much did I withdraw in July?")

        assertThat(filter).isEqualTo(TransactionFilter(TransactionType.WITHDRAWAL, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
    }

    @Test
    fun `drops an invalid type from the model rather than passing it through`() {
        mockOllamaResponse(200, objectMapper.writeValueAsString(mapOf("type" to "NOT_A_REAL_TYPE", "fromDate" to "", "toDate" to "")))

        val filter = translator.translate("anything")

        assertThat(filter.type).isNull()
    }

    @Test
    fun `drops a malformed date from the model rather than passing it through`() {
        mockOllamaResponse(
            200,
            objectMapper.writeValueAsString(mapOf("type" to "ANY", "fromDate" to "not-a-date", "toDate" to "2026-13-99")),
        )

        val filter = translator.translate("anything")

        assertThat(filter.fromDate).isNull()
        assertThat(filter.toDate).isNull()
    }

    @Test
    fun `falls back to no filter rather than throwing when the ollama call fails`() {
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws IOException("connection refused")

        val filter = translator.translate("anything")

        assertThat(filter).isEqualTo(TransactionFilter())
    }

    @Test
    fun `falls back to no filter when ollama responds with a non-ok status`() {
        mockOllamaResponse(500, null)

        val filter = translator.translate("anything")

        assertThat(filter).isEqualTo(TransactionFilter())
    }
}
