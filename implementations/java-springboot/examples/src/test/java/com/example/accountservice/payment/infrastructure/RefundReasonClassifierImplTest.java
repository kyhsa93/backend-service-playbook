package com.example.accountservice.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.accountservice.config.LlmProperties;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundReasonClassifierImplTest {

    @Mock private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RefundReasonClassifierImpl classifier;

    @BeforeEach
    void setUp() {
        LlmProperties llmProperties = new LlmProperties("http://localhost:11434", "qwen2.5:1.5b");
        classifier = new RefundReasonClassifierImpl(llmProperties, objectMapper, httpClient);
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
    void classify_when_the_model_returns_a_valid_category_then_returns_it() throws Exception {
        mockOllamaResponse(
                200, objectMapper.writeValueAsString(Map.of("category", "DEFECTIVE_PRODUCT")));

        RefundReasonCategory category = classifier.classify("The item arrived broken");

        assertThat(category).isEqualTo(RefundReasonCategory.DEFECTIVE_PRODUCT);
    }

    @Test
    void classify_when_the_model_returns_an_out_of_taxonomy_category_then_falls_back_to_OTHER()
            throws Exception {
        mockOllamaResponse(
                200, objectMapper.writeValueAsString(Map.of("category", "NOT_A_REAL_CATEGORY")));

        RefundReasonCategory category = classifier.classify("Some reason");

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER);
    }

    @Test
    void classify_when_the_ollama_call_fails_then_falls_back_to_OTHER_rather_than_throwing()
            throws Exception {
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection refused"));

        RefundReasonCategory category = classifier.classify("Some reason");

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER);
    }

    @Test
    void classify_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_OTHER()
            throws Exception {
        mockOllamaResponse(500, null);

        RefundReasonCategory category = classifier.classify("Some reason");

        assertThat(category).isEqualTo(RefundReasonCategory.OTHER);
    }
}
