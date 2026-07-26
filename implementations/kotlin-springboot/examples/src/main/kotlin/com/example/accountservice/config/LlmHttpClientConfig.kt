package com.example.accountservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Duration

/**
 * A single shared [HttpClient] bean for the LLM-calling Technical Services (see
 * account/infrastructure/NlTransactionQueryTranslatorImpl.kt/NlTransactionAnswerComposerImpl.kt) —
 * Ollama has no official Kotlin/JVM SDK, so they talk to its native `/api/chat` endpoint directly
 * over plain HTTP. Exposing it as a bean (rather than each Impl constructing its own internally)
 * keeps the constructor injectable, so a unit test can supply a mock [HttpClient] instead of making
 * a real network call.
 */
@Configuration
class LlmHttpClientConfig {
    @Bean
    fun llmHttpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}
