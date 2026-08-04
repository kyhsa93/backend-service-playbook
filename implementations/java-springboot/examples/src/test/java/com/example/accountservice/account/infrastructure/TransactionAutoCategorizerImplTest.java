package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.domain.TransactionCategory;
import com.example.accountservice.config.LlmProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TransactionAutoCategorizerImplTest {

    @Mock private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TransactionAutoCategorizerImpl categorizer;

    @BeforeEach
    void setUp() {
        LlmProperties llmProperties = new LlmProperties("http://localhost:11434", "qwen2.5:1.5b");
        categorizer = new TransactionAutoCategorizerImpl(llmProperties, objectMapper, httpClient);
    }

    @SuppressWarnings("unchecked")
    private void mockOllamaResponse(int statusCode, String content) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (content != null) {
            String body =
                    objectMapper.writeValueAsString(Map.of("message", Map.of("content", content)));
            when(response.body()).thenReturn(body);
        }
        when(httpClient.<String>send(any(), any())).thenReturn(response);
    }

    @Test
    void categorize_when_the_model_returns_a_valid_category_then_returns_it() throws Exception {
        mockOllamaResponse(200, objectMapper.writeValueAsString(Map.of("category", "FOOD")));

        TransactionCategory category = categorizer.categorize("Starbucks Gangnam", 5500);

        assertThat(category).isEqualTo(TransactionCategory.FOOD);
    }

    @Test
    void categorize_when_the_model_returns_an_out_of_taxonomy_category_then_falls_back_to_OTHER()
            throws Exception {
        mockOllamaResponse(
                200, objectMapper.writeValueAsString(Map.of("category", "NOT_A_REAL_CATEGORY")));

        TransactionCategory category = categorizer.categorize("Unknown Payee", 1000);

        assertThat(category).isEqualTo(TransactionCategory.OTHER);
    }

    @Test
    void categorize_when_the_ollama_call_fails_then_falls_back_to_OTHER_rather_than_throwing()
            throws Exception {
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection refused"));

        TransactionCategory category = categorizer.categorize("Anything", 1000);

        assertThat(category).isEqualTo(TransactionCategory.OTHER);
    }

    @Test
    void categorize_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_OTHER()
            throws Exception {
        mockOllamaResponse(500, null);

        TransactionCategory category = categorizer.categorize("Anything", 1000);

        assertThat(category).isEqualTo(TransactionCategory.OTHER);
    }
}
