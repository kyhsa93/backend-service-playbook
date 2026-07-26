package com.example.accountservice.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single shared {@link HttpClient} bean for the LLM-calling Technical Services (see
 * account/infrastructure/NlTransactionQueryTranslatorImpl.java/NlTransactionAnswerComposerImpl.java)
 * — Ollama has no official Java SDK, so they talk to its native {@code /api/chat} endpoint directly
 * over plain HTTP. Exposing it as a bean (rather than each Impl constructing its own internally)
 * keeps the constructor injectable, so a unit test can supply a mock {@link HttpClient} instead of
 * making a real network call.
 */
@Configuration
public class LlmHttpClientConfig {

    @Bean
    public HttpClient llmHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
}
