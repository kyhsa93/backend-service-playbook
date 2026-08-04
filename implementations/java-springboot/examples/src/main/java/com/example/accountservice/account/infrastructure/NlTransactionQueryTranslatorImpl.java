package com.example.accountservice.account.infrastructure;

import com.example.accountservice.account.application.service.NlTransactionQueryTranslator;
import com.example.accountservice.account.application.service.TransactionFilter;
import com.example.accountservice.account.domain.TransactionType;
import com.example.accountservice.common.UtcClock;
import com.example.accountservice.config.LlmProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the "Retrieve"-preparation half of a structured-data RAG pipeline ({@link
 * com.example.accountservice.account.application.service.NlTransactionAnswerComposer} is the
 * "Generate" half). Talks to Ollama's native {@code /api/chat} endpoint over plain HTTP via {@link
 * HttpClient} (Ollama has no official Java SDK), the same self-hosted {@code qwen2.5:1.5b} setup
 * used elsewhere in this repo for LLM Technical Services.
 */
@Component
@RequiredArgsConstructor
public class NlTransactionQueryTranslatorImpl implements NlTransactionQueryTranslator {

    private static final Logger log =
            LoggerFactory.getLogger(NlTransactionQueryTranslatorImpl.class);

    private static final List<String> TYPES = List.of("DEPOSIT", "WITHDRAWAL", "INTEREST");

    // An empty filter degrades gracefully to "no narrowing" — AskTransactionHistoryService still
    // runs AccountQuery.findTransactions with no type/date constraint, so a translation failure
    // never blocks the question from being answered against the requester's most recent
    // transactions.
    private static final TransactionFilter FALLBACK_FILTER =
            new TransactionFilter(null, null, null);

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Override
    public TransactionFilter translate(String question) {
        try {
            Map<String, Object> responseFormat =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(
                                    "type",
                                            Map.of(
                                                    "type",
                                                    "string",
                                                    "enum",
                                                    List.of(
                                                            "DEPOSIT",
                                                            "WITHDRAWAL",
                                                            "INTEREST",
                                                            "ANY")),
                                    "fromDate", Map.of("type", "string"),
                                    "toDate", Map.of("type", "string")),
                            "required",
                            List.of("type", "fromDate", "toDate"),
                            "additionalProperties",
                            false);

            Map<String, Object> requestBody =
                    Map.of(
                            "model",
                            llmProperties.model(),
                            "stream",
                            false,
                            "messages",
                            List.of(
                                    Map.of("role", "system", "content", buildSystemPrompt()),
                                    Map.of("role", "user", "content", question)),
                            "format",
                            responseFormat);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(llmProperties.ollamaBaseUrl() + "/api/chat"))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(requestBody)))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn(
                        "Transaction query translation failed, using no filter: status={}",
                        response.statusCode());
                return FALLBACK_FILTER;
            }

            OllamaChatResponse parsedResponse =
                    objectMapper.readValue(response.body(), OllamaChatResponse.class);
            String content =
                    parsedResponse.message() != null ? parsedResponse.message().content() : null;
            if (content == null || content.isBlank()) {
                return FALLBACK_FILTER;
            }

            ParsedFilter parsed = objectMapper.readValue(content, ParsedFilter.class);
            TransactionType type =
                    parsed.type() != null && TYPES.contains(parsed.type())
                            ? TransactionType.valueOf(parsed.type())
                            : null;
            LocalDate fromDate = parseIsoDateOrNull(parsed.fromDate());
            LocalDate toDate = parseIsoDateOrNull(parsed.toDate());
            return new TransactionFilter(type, fromDate, toDate);
        } catch (Exception e) {
            // A translation failure is a technical-infrastructure concern, not a domain error — it
            // must never block the question from being answered. Swallow it here at the boundary.
            log.warn("Transaction query translation failed, using no filter: {}", e.getMessage());
            return FALLBACK_FILTER;
        }
    }

    private static LocalDate parseIsoDateOrNull(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildSystemPrompt() {
        String today = UtcClock.today().toString();
        return "You translate a user's natural-language question about their own bank account"
                + " transaction history into a structured JSON filter. Today's date is "
                + today
                + ". Resolve any relative date expression (\"this month\", \"last week\") against"
                + " that date.\n"
                + "Fields: \"type\" — DEPOSIT, WITHDRAWAL, INTEREST, or ANY if the question doesn't"
                + " ask about a specific type. \"fromDate\"/\"toDate\" — an ISO 8601 date"
                + " (YYYY-MM-DD), or an empty string if the question implies no date boundary on"
                + " that side.\n"
                + "Only extract constraints the question actually states or clearly implies. Never"
                + " invent a date range or transaction type the question doesn't support. Respond"
                + " only through the given schema.";
    }

    private record OllamaChatResponse(OllamaMessage message) {}

    private record OllamaMessage(String content) {}

    private record ParsedFilter(String type, String fromDate, String toDate) {}
}
