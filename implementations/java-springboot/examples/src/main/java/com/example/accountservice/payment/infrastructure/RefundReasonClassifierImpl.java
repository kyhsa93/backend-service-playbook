package com.example.accountservice.payment.infrastructure;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.example.accountservice.common.service.SecretService;
import com.example.accountservice.config.RefundClassifierProperties;
import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.example.accountservice.payment.domain.RefundReasonClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The real implementation of {@link RefundReasonClassifier} — calls the Claude API with a
 * JSON-schema-constrained response and falls back to a neutral, non-blocking result on any failure.
 * A classification outage must never block a refund request, so the failure is swallowed here at
 * this Infrastructure boundary rather than surfaced as a domain error.
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

    // Used whenever the classification can't be trusted (API error, refusal, malformed output). A
    // neutral 'other'/no-fraud-signal result never blocks the refund flow on its own —
    // RefundEligibilityService's other checks still run against it.
    private static final RefundReasonClassification FALLBACK_CLASSIFICATION =
            new RefundReasonClassification(RefundReasonCategory.OTHER, 0);

    private static final String SYSTEM_PROMPT =
            "You classify a customer refund request's free-text reason. Respond only through the"
                    + " given schema. Base fraudRiskScore purely on linguistic signals in the text"
                    + " itself (vagueness, internal inconsistency, urgency/pressure language, or an"
                    + " admission unrelated to the product) — never infer a high score just because"
                    + " the category is fraud_suspected, and never infer a low score just because it"
                    + " isn't.";

    private final RefundClassifierProperties refundClassifierProperties;
    private final SecretService secretService;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private volatile AnthropicClient client;

    public RefundReasonClassifierImpl(
            RefundClassifierProperties refundClassifierProperties,
            SecretService secretService,
            Environment environment,
            ObjectMapper objectMapper) {
        this.refundClassifierProperties = refundClassifierProperties;
        this.secretService = secretService;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    // The API key is looked up from Secrets Manager only in production, exactly like
    // JwtProperties/SecretsEnvironmentPostProcessor (see docs/architecture/secret-manager.md) —
    // every other environment (dev/test) uses the environment-variable-backed
    // RefundClassifierProperties.anthropicApiKey() directly, with no network call. Unlike the JWT
    // secret (resolved eagerly at Environment-post-processing time, before the ApplicationContext
    // exists), this lookup happens lazily on first use, inside this DI-managed Technical Service,
    // via the injected SecretService. Both gate on the same Spring profile check
    // (Environment.acceptsProfiles(Profiles.of("prod"))), not an environment-variable value.
    private AnthropicClient getClient() {
        AnthropicClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                String apiKey =
                        environment.acceptsProfiles(Profiles.of("prod"))
                                ? secretService.getSecret("app/anthropic")
                                : refundClassifierProperties.anthropicApiKey();
                client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
            return client;
        }
    }

    @Override
    public RefundReasonClassification classify(String reason) {
        try {
            Map<String, Object> schema =
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

            MessageCreateParams params =
                    MessageCreateParams.builder()
                            .model(refundClassifierProperties.model())
                            .maxTokens(256L)
                            .system(SYSTEM_PROMPT)
                            .addUserMessage(reason)
                            .outputConfig(
                                    OutputConfig.builder()
                                            .format(
                                                    JsonOutputFormat.builder()
                                                            .schema(JsonValue.from(schema))
                                                            .build())
                                            .build())
                            .build();

            Message response = getClient().messages().create(params);

            if (response.stopReason().isPresent()
                    && response.stopReason().get() == StopReason.REFUSAL) {
                return FALLBACK_CLASSIFICATION;
            }

            String text =
                    response.content().stream()
                            .flatMap(block -> block.text().stream())
                            .findFirst()
                            .map(TextBlock::text)
                            .orElse(null);
            if (text == null) {
                return FALLBACK_CLASSIFICATION;
            }

            ClassificationResponse parsed =
                    objectMapper.readValue(text, ClassificationResponse.class);
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

    private record ClassificationResponse(String category, double fraudRiskScore) {}
}
