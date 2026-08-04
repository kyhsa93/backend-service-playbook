package com.example.accountservice.payment.infrastructure

import com.example.accountservice.config.LlmProperties
import com.example.accountservice.payment.domain.RefundReasonCategory
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpResponse

class RefundReasonClassifierImplTest {
    private val httpClient = mockk<HttpClient>()
    private val objectMapper = jacksonObjectMapper()
    private lateinit var classifier: RefundReasonClassifierImpl

    @BeforeEach
    fun setUp() {
        classifier = RefundReasonClassifierImpl(LlmProperties("http://localhost:11434", "qwen2.5:1.5b"), objectMapper, httpClient)
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
    fun `returns the model's category when the response is valid`() {
        mockOllamaResponse(200, objectMapper.writeValueAsString(mapOf("category" to "DEFECTIVE_PRODUCT")))

        val category = classifier.classify("The item arrived broken")

        assertThat(category).isEqualTo(RefundReasonCategory.DEFECTIVE_PRODUCT)
    }

    @Test
    fun `falls back to OTHER rather than passing through an out-of-taxonomy category from the model`() {
        mockOllamaResponse(200, objectMapper.writeValueAsString(mapOf("category" to "NOT_A_REAL_CATEGORY")))

        val category = classifier.classify("Some reason")

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER)
    }

    @Test
    fun `falls back to OTHER rather than throwing when the ollama call fails`() {
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws IOException("connection refused")

        val category = classifier.classify("Some reason")

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER)
    }

    @Test
    fun `falls back to OTHER when ollama responds with a non-ok status`() {
        mockOllamaResponse(500, null)

        val category = classifier.classify("Some reason")

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER)
    }
}
