package com.example.accountservice.account.infrastructure;

import com.example.accountservice.account.application.service.TransactionAutoCategorizer;
import com.example.accountservice.account.domain.TransactionCategory;
import com.example.accountservice.config.LlmProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same self-hosted {@code qwen2.5:1.5b} Ollama setup as {@link
 * NlTransactionQueryTranslatorImpl}/{@link NlTransactionAnswerComposerImpl}, just a different
 * prompt/schema for a different job (classification instead of query translation or answer
 * generation).
 */
@Component
@RequiredArgsConstructor
public class TransactionAutoCategorizerImpl implements TransactionAutoCategorizer {

    private static final Logger log = LoggerFactory.getLogger(TransactionAutoCategorizerImpl.class);

    private static final List<String> CATEGORIES =
            Arrays.stream(TransactionCategory.values()).map(Enum::name).toList();

    // A classification failure (the LLM call itself, or an out-of-taxonomy answer) is a
    // technical-infrastructure concern, not a domain error — this is a best-effort enrichment, not
    // a financial correctness concern, so it degrades to OTHER rather than ever blocking or
    // retrying indefinitely. The same posture as NlTransactionQueryTranslatorImpl falling back to
    // no filter.
    private static final TransactionCategory FALLBACK_CATEGORY = TransactionCategory.OTHER;

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Override
    public TransactionCategory categorize(String merchantName, long amount) {
        try {
            Map<String, Object> responseFormat =
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("category", Map.of("type", "string", "enum", CATEGORIES)),
                            "required",
                            List.of("category"),
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
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            "Merchant: " + merchantName + "\nAmount: " + amount)),
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
                        "Transaction categorization failed, using fallback category: status={}",
                        response.statusCode());
                return FALLBACK_CATEGORY;
            }

            OllamaChatResponse parsedResponse =
                    objectMapper.readValue(response.body(), OllamaChatResponse.class);
            String content =
                    parsedResponse.message() != null ? parsedResponse.message().content() : null;
            if (content == null || content.isBlank()) {
                return FALLBACK_CATEGORY;
            }

            ParsedCategory parsed = objectMapper.readValue(content, ParsedCategory.class);
            return parsed.category() != null && CATEGORIES.contains(parsed.category())
                    ? TransactionCategory.valueOf(parsed.category())
                    : FALLBACK_CATEGORY;
        } catch (Exception e) {
            // A classification failure is a technical-infrastructure concern, not a domain error —
            // it must never block the withdrawal that already happened. Swallow it here at the
            // boundary.
            log.warn(
                    "Transaction categorization failed, using fallback category: {}",
                    e.getMessage());
            return FALLBACK_CATEGORY;
        }
    }

    private static String buildSystemPrompt() {
        return "You classify a bank withdrawal into exactly one spending category based on its"
                + " payee/merchant name and amount. Categories: "
                + String.join(", ", CATEGORIES)
                + ". Use OTHER only when none of the other categories plausibly fit. Respond only"
                + " through the given schema.";
    }

    private record OllamaChatResponse(OllamaMessage message) {}

    private record OllamaMessage(String content) {}

    private record ParsedCategory(String category) {}
}
