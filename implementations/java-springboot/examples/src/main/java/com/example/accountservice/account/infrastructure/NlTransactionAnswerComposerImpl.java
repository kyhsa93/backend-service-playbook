package com.example.accountservice.account.infrastructure;

import com.example.accountservice.account.application.query.GetTransactionsResult.TransactionSummary;
import com.example.accountservice.account.application.service.NlTransactionAnswerComposer;
import com.example.accountservice.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) generating a natural-language
 * answer from already-retrieved transaction records — the "Generate" half of a structured-data RAG
 * pipeline ({@link
 * com.example.accountservice.account.application.service.NlTransactionQueryTranslator} is the
 * "Retrieve"-preparation half). Uses the same self-hosted Ollama setup, without a
 * JSON-schema-constrained response since the output here is free-form prose, not a structured value
 * the caller parses.
 */
@Component
@RequiredArgsConstructor
public class NlTransactionAnswerComposerImpl implements NlTransactionAnswerComposer {

    private static final Logger log =
            LoggerFactory.getLogger(NlTransactionAnswerComposerImpl.class);

    // Detect-and-match-the-question's-language is asked of the model explicitly, mirroring the
    // same live-tested prompt shape used elsewhere in this repo for a small self-hosted model
    // (qwen2.5:1.5b) — see nestjs's NlTransactionAnswerComposerImpl for the same wording and the
    // live-test note on its language-matching limitation.
    private static final String SYSTEM_PROMPT =
            "You answer a user's question about their own bank account transactions using ONLY the"
                    + " transaction data listed below — never mention or infer a transaction that"
                    + " isn't in that list. Concisely (2-3 sentences). If the listed data doesn't"
                    + " contain enough information to answer (e.g. it's empty), say so plainly"
                    + " instead of guessing.\n"
                    + "IMPORTANT: detect the language the question itself is written in, and write"
                    + " your entire answer in that same language — e.g. a Korean question always"
                    + " gets a Korean answer, an English question always gets an English answer,"
                    + " regardless of what language this instruction or the transaction data is"
                    + " in.";

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Override
    public String compose(String question, List<TransactionSummary> transactions) {
        try {
            Map<String, Object> requestBody =
                    Map.of(
                            "model",
                            llmProperties.model(),
                            "stream",
                            false,
                            "messages",
                            List.of(
                                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                                    Map.of(
                                            "role",
                                            "user",
                                            "content",
                                            "Question: "
                                                    + question
                                                    + "\n\nTransactions:\n"
                                                    + formatTransactions(transactions))));

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
                        "Answer composition failed, using fallback: status={}",
                        response.statusCode());
                return fallbackAnswer(transactions);
            }

            OllamaChatResponse parsedResponse =
                    objectMapper.readValue(response.body(), OllamaChatResponse.class);
            String content =
                    parsedResponse.message() != null ? parsedResponse.message().content() : null;
            return content != null && !content.isBlank()
                    ? content.trim()
                    : fallbackAnswer(transactions);
        } catch (Exception e) {
            // A composition failure is a technical-infrastructure concern, not a domain error — it
            // must never block the question from getting *an* answer, even a plain one.
            log.warn("Answer composition failed, using fallback: {}", e.getMessage());
            return fallbackAnswer(transactions);
        }
    }

    private static String formatTransactions(List<TransactionSummary> transactions) {
        if (transactions.isEmpty()) return "(no matching transactions)";
        return transactions.stream()
                .map(
                        t ->
                                "- "
                                        + t.type()
                                        + " "
                                        + t.amount().amount()
                                        + " "
                                        + t.amount().currency()
                                        + " on "
                                        + t.createdAt().toLocalDate())
                .collect(Collectors.joining("\n"));
    }

    // A plain, non-blocking fallback used whenever the LLM call fails — describes the same data a
    // working call would have been grounded in, just without natural-language phrasing.
    private static String fallbackAnswer(List<TransactionSummary> transactions) {
        if (transactions.isEmpty()) return "No matching transactions were found.";
        return "Found "
                + transactions.size()
                + " matching transaction(s):\n"
                + formatTransactions(transactions);
    }

    private record OllamaChatResponse(OllamaMessage message) {}

    private record OllamaMessage(String content) {}
}
