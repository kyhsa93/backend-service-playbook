package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.config.RefundClassifierProperties;
import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.example.accountservice.payment.domain.RefundReasonClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The real implementation of {@link RefundReasonClassifier} — calls a self-hosted, open-source LLM
 * (Ollama, running the lightweight {@code qwen2.5:1.5b} model — see {@code docker-compose.yml}'s
 * {@code ollama}/{@code ollama-init} services) with a JSON-schema-constrained response, and falls
 * back to a neutral, non-blocking result on any failure. A classification outage must never block a
 * refund request, so the failure is swallowed here at this Infrastructure boundary rather than
 * surfaced as a domain error.
 *
 * <p>Talks to Ollama's native {@code /api/chat} endpoint over plain HTTP via {@link HttpClient}
 * rather than a vendor SDK, since Ollama has no official Java client. {@code qwen2.5:1.5b} (not the
 * smaller {@code 0.5b} variant) was chosen after live-testing both: {@code 0.5b} misclassified
 * plain billing complaints ("charged twice, refund the duplicate") as {@code fraud_suspected} with
 * {@code fraudRiskScore} 1.0, which would wrongly reject a legitimate refund at the 0.7 threshold
 * in {@code RefundEligibilityService}. {@code 1.5b} is meaningfully more reliable while still small
 * enough to run locally.
 */
@Component
public class RefundReasonClassifierImpl implements RefundReasonClassifier {

    private static final Logger log = LoggerFactory.getLogger(RefundReasonClassifierImpl.class);

    private static final List<String> CATEGORIES =
            List.of(
                    "defective_product",
                    "not_as_described",
                    "duplicate_charge",
                    "changed_mind",
                    "fraud_suspected",
                    "other");

    // Used whenever the classification can't be trusted (Ollama unreachable, non-2xx response,
    // malformed output). A neutral 'other'/no-fraud-signal result never blocks the refund flow on
    // its own — RefundEligibilityService's other checks still run against it.
    private static final RefundReasonClassification FALLBACK_CLASSIFICATION =
            new RefundReasonClassification(RefundReasonCategory.OTHER, 0);

    // Deliberately explicit and example-anchored — the classifier runs on a small, self-hosted
    // model (qwen2.5:1.5b) that, tested live against this exact prompt shape, otherwise conflates
    // ordinary billing complaints ("charged twice, refund the duplicate") with fraud_suspected. A
    // calm, single-issue complaint is never fraud_suspected on its own; only report fraud when the
    // text itself shows deception or denies placing the order.
    private static final String SYSTEM_PROMPT =
            "You classify a customer refund request's free-text reason into exactly one category and"
                    + " a fraud-risk score from 0 to 1. Respond only through the given schema.\n"
                    + "Categories: defective_product (item broken/damaged/malfunctioning),"
                    + " not_as_described (wrong item or mismatched description), duplicate_charge"
                    + " (billed more than once for the same order), changed_mind (no longer wants the"
                    + " item, no product issue), fraud_suspected (the customer explicitly states they"
                    + " never placed the order, or the message itself shows deception/inconsistency),"
                    + " other.\n"
                    + "A plain, calm complaint about being billed twice is duplicate_charge with a LOW"
                    + " fraud score near 0 — it is not fraud_suspected. Only use fraud_suspected for"
                    + " signs of deception, not for an ordinary billing complaint.";

    // Ollama's `format` field takes a raw JSON Schema and constrains decoding to match it (grammar-
    // based — this guarantees syntactically valid JSON matching this shape regardless of model
    // size; it does NOT guarantee the category/score judgment itself is reliable at small sizes,
    // which is why SYSTEM_PROMPT above is unusually explicit and example-anchored).
    private static final Map<String, Object> RESPONSE_FORMAT =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "category", Map.of("type", "string", "enum", CATEGORIES),
                            "fraudRiskScore", Map.of("type", "number")),
                    "required",
                    List.of("category", "fraudRiskScore"),
                    "additionalProperties",
                    false);

    private final RefundClassifierProperties refundClassifierProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RefundReasonClassifierImpl(
            RefundClassifierProperties refundClassifierProperties, ObjectMapper objectMapper) {
        this.refundClassifierProperties = refundClassifierProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public RefundReasonClassification classify(String reason) {
        try {
            Map<String, Object> requestBody =
                    Map.of(
                            "model",
                            refundClassifierProperties.model(),
                            "stream",
                            false,
                            "messages",
                            List.of(
                                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                                    Map.of("role", "user", "content", reason)),
                            "format",
                            RESPONSE_FORMAT);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            refundClassifierProperties.ollamaBaseUrl()
                                                    + "/api/chat"))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(requestBody)))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn(
                        "Refund reason classification failed, using fallback: status={}",
                        response.statusCode());
                return FALLBACK_CLASSIFICATION;
            }

            OllamaChatResponse parsedResponse =
                    objectMapper.readValue(response.body(), OllamaChatResponse.class);
            String content =
                    parsedResponse.message() != null ? parsedResponse.message().content() : null;
            if (content == null || content.isBlank()) {
                return FALLBACK_CLASSIFICATION;
            }

            ClassificationResponse parsed =
                    objectMapper.readValue(content, ClassificationResponse.class);
            if (parsed.category() == null || !CATEGORIES.contains(parsed.category())) {
                return FALLBACK_CLASSIFICATION;
            }

            double fraudRiskScore = Math.min(1, Math.max(0, parsed.fraudRiskScore()));
            RefundReasonCategory category =
                    RefundReasonCategory.valueOf(parsed.category().toUpperCase(Locale.ROOT));
            return new RefundReasonClassification(category, fraudRiskScore);
        } catch (Exception e) {
            // A classification failure is a technical-infrastructure concern, not a domain error —
            // it must never block a refund request. Swallow it here at the boundary and fall back.
            log.warn("Refund reason classification failed, using fallback: {}", e.getMessage());
            return FALLBACK_CLASSIFICATION;
        }
    }

    private record OllamaChatResponse(OllamaMessage message) {}

    private record OllamaMessage(String content) {}

    private record ClassificationResponse(String category, double fraudRiskScore) {}
}
