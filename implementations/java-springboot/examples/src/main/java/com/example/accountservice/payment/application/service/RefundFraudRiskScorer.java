package com.example.accountservice.payment.application.service;

import com.example.accountservice.payment.domain.RefundRiskFeatures;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) abstracting an ML fraud-risk
 * model — trained on the requester's refund/payment *history pattern* (frequency, amount ratio,
 * time since payment), rather than the free-text reason {@link RefundReasonClassifier} handles.
 * {@code RefundEligibilityService} (a Domain Service) never depends on this interface and never
 * trains or calls a model itself — it only ever receives the already-computed score as a plain
 * {@code double} and applies its own fixed threshold. Two implementations exist side by side
 * ({@code infrastructure/RefundFraudRiskScorerNativeImpl}, {@code
 * infrastructure/RefundFraudRiskScorerHttpImpl}); which one is wired up is a config choice (see
 * {@code config/FraudScorerProperties}), never a Domain concern — the same swap-via-config point
 * {@code RefundReasonClassifier}'s Ollama backend once demonstrated, but now as a live toggle
 * between two implementations that coexist in the same build.
 */
public interface RefundFraudRiskScorer {

    double score(RefundRiskFeatures features);
}
