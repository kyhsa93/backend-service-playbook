package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.config.FraudScorerProperties;
import com.example.accountservice.payment.application.service.RefundFraudRiskScorer;
import com.example.accountservice.payment.domain.RefundRiskFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Calls the shared {@code services/fraud-risk-scorer} microservice (Python + scikit-learn, trained
 * on the same synthetic dataset as {@link RefundFraudRiskScorerNativeImpl}'s hand-rolled model)
 * over plain HTTP — the "one shared model" side of the pair. Every one of the 5 language
 * implementations calls this same service; see {@code FraudScorerProperties} for how {@code
 * fraud-scorer.mode=http} selects this impl over the in-process native one. Falls back to a neutral
 * 0 on any failure (unreachable, non-2xx, malformed body) — a scoring outage must never block a
 * refund request, the same fallback stance as {@code RefundReasonClassifierImpl}.
 */
@Component
@ConditionalOnProperty(prefix = "fraud-scorer", name = "mode", havingValue = "http")
public class RefundFraudRiskScorerHttpImpl implements RefundFraudRiskScorer {

    private static final Logger log = LoggerFactory.getLogger(RefundFraudRiskScorerHttpImpl.class);

    // Used whenever the score can't be trusted (the shared scorer unreachable, non-2xx response,
    // malformed output). A neutral 0 never blocks the refund flow on its own —
    // RefundEligibilityService's other checks still run against it.
    private static final double FALLBACK_SCORE = 0;

    private final FraudScorerProperties fraudScorerProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RefundFraudRiskScorerHttpImpl(
            FraudScorerProperties fraudScorerProperties, ObjectMapper objectMapper) {
        this.fraudScorerProperties = fraudScorerProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public double score(RefundRiskFeatures features) {
        try {
            Map<String, Object> requestBody =
                    Map.of(
                            "refundCountLast30Days", features.refundCountLast30Days(),
                            "rejectedRefundCountLast30Days",
                                    features.rejectedRefundCountLast30Days(),
                            "refundToPaymentAmountRatio", features.refundToPaymentAmountRatio(),
                            "minutesSincePayment", features.minutesSincePayment());

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(fraudScorerProperties.baseUrl() + "/score"))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(requestBody)))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn(
                        "Fraud risk scoring failed, using fallback: status={}",
                        response.statusCode());
                return FALLBACK_SCORE;
            }

            ScoreResponse parsed = objectMapper.readValue(response.body(), ScoreResponse.class);
            if (parsed.riskScore() == null) {
                return FALLBACK_SCORE;
            }
            return Math.min(1, Math.max(0, parsed.riskScore()));
        } catch (Exception e) {
            // A scoring failure is a technical-infrastructure concern, not a domain error — it
            // must never block a refund request. Swallow it here at the boundary and fall back.
            log.warn("Fraud risk scoring failed, using fallback: {}", e.getMessage());
            return FALLBACK_SCORE;
        }
    }

    private record ScoreResponse(Double riskScore) {}
}
