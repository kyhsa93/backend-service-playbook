package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.service.TransactionFilter;
import com.example.accountservice.account.domain.TransactionType;
import com.example.accountservice.config.LlmProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NlTransactionQueryTranslatorImplTest {

    @Mock private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NlTransactionQueryTranslatorImpl translator;

    @BeforeEach
    void setUp() {
        LlmProperties llmProperties = new LlmProperties("http://localhost:11434", "qwen2.5:1.5b");
        translator = new NlTransactionQueryTranslatorImpl(llmProperties, objectMapper, httpClient);
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
    void translate_when_the_model_returns_a_valid_type_and_dates_then_returns_them_as_the_filter()
            throws Exception {
        mockOllamaResponse(
                200,
                objectMapper.writeValueAsString(
                        Map.of(
                                "type", "WITHDRAWAL",
                                "fromDate", "2026-07-01",
                                "toDate", "2026-07-31")));

        TransactionFilter filter = translator.translate("How much did I withdraw in July?");

        assertThat(filter)
                .isEqualTo(
                        new TransactionFilter(
                                TransactionType.WITHDRAWAL,
                                LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 7, 31)));
    }

    @Test
    void
            translate_when_the_model_returns_an_invalid_type_then_drops_it_rather_than_passing_it_through()
                    throws Exception {
        mockOllamaResponse(
                200,
                objectMapper.writeValueAsString(
                        Map.of("type", "NOT_A_REAL_TYPE", "fromDate", "", "toDate", "")));

        TransactionFilter filter = translator.translate("anything");

        assertThat(filter.type()).isNull();
    }

    @Test
    void
            translate_when_the_model_returns_a_malformed_date_then_drops_it_rather_than_passing_it_through()
                    throws Exception {
        mockOllamaResponse(
                200,
                objectMapper.writeValueAsString(
                        Map.of("type", "ANY", "fromDate", "not-a-date", "toDate", "2026-13-99")));

        TransactionFilter filter = translator.translate("anything");

        assertThat(filter.fromDate()).isNull();
        assertThat(filter.toDate()).isNull();
    }

    @Test
    void translate_when_the_ollama_call_fails_then_falls_back_to_no_filter_rather_than_throwing()
            throws Exception {
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection refused"));

        TransactionFilter filter = translator.translate("anything");

        assertThat(filter).isEqualTo(new TransactionFilter(null, null, null));
    }

    @Test
    void translate_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_no_filter()
            throws Exception {
        mockOllamaResponse(500, null);

        TransactionFilter filter = translator.translate("anything");

        assertThat(filter).isEqualTo(new TransactionFilter(null, null, null));
    }
}
