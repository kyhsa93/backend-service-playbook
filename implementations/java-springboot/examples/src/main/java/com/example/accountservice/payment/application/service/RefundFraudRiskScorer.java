package com.example.accountservice.payment.application.service;

import com.example.accountservice.payment.domain.RefundRiskFeatures;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) abstracting an ML fraud-risk
 * model — trained on the requester's refund/payment *history pattern*: refund frequency, amount
 * ratio, time since payment — structured facts drawn from the requester's own history rather than
 * anything the requester supplies directly. {@code RefundEligibilityService} (a Domain Service)
 * never depends on this interface and never trains or calls a model itself — it only ever receives
 * the already-computed score as a plain {@code double} and applies its own fixed threshold. Two
 * implementations exist side by side ({@code infrastructure/RefundFraudRiskScorerNativeImpl},
 * {@code infrastructure/RefundFraudRiskScorerHttpImpl}); which one is wired up is a config choice
 * (see {@code config/FraudScorerProperties}), never a Domain concern — a live toggle between an
 * in-process model and a shared microservice, both coexisting in the same build.
 */
public interface RefundFraudRiskScorer {

    double score(RefundRiskFeatures features);
}
