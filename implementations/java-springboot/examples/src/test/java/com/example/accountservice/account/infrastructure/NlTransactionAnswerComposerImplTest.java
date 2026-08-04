package com.example.accountservice.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.accountservice.account.application.query.GetTransactionsResult.MoneyResult;
import com.example.accountservice.account.application.query.GetTransactionsResult.TransactionSummary;
import com.example.accountservice.config.LlmProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NlTransactionAnswerComposerImplTest {

    @Mock private HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NlTransactionAnswerComposerImpl composer;

    private final List<TransactionSummary> transactions =
            List.of(
                    new TransactionSummary(
                            "t1",
                            "DEPOSIT",
                            new MoneyResult(1000, "KRW"),
                            null,
                            null,
                            LocalDateTime.of(2026, 7, 10, 0, 0)));

    @BeforeEach
    void setUp() {
        LlmProperties llmProperties = new LlmProperties("http://localhost:11434", "qwen2.5:1.5b");
        composer = new NlTransactionAnswerComposerImpl(llmProperties, objectMapper, httpClient);
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
    void compose_when_the_model_answers_then_returns_the_trimmed_answer() throws Exception {
        mockOllamaResponse(200, "  You deposited 1000 KRW.  ");

        String answer = composer.compose("How much did I deposit?", transactions);

        assertThat(answer).isEqualTo("You deposited 1000 KRW.");
    }

    @Test
    void
            compose_when_the_ollama_call_fails_then_falls_back_to_a_plain_summary_naming_the_actual_count()
                    throws Exception {
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection refused"));

        String answer = composer.compose("How much did I deposit?", transactions);

        assertThat(answer).contains("Found 1 matching transaction(s)");
    }

    @Test
    void compose_when_there_are_no_transactions_and_the_call_fails_then_says_so_plainly()
            throws Exception {
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection refused"));

        String answer = composer.compose("How much did I withdraw?", List.of());

        assertThat(answer).isEqualTo("No matching transactions were found.");
    }
}
