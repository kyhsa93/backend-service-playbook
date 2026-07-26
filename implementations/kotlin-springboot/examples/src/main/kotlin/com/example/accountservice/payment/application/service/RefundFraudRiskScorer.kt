package com.example.accountservice.payment.application.service

import com.example.accountservice.payment.domain.RefundRiskFeatures

/**
 * A Technical Service (see root `docs/architecture/domain-service.md`) abstracting an ML fraud-risk
 * model — trained on the requester's own structured refund/payment *history* (refund count,
 * rejection count, amount ratio, time since payment), not user-supplied free text.
 * [com.example.accountservice.payment.domain.RefundEligibilityService] (a Domain Service) never
 * depends on this interface and never trains or calls a model itself — it only ever receives the
 * already-computed score as a plain [Double] and applies its own fixed threshold. Two implementations
 * exist side by side (`RefundFraudRiskScorerNativeImpl`, `RefundFraudRiskScorerHttpImpl`); which one
 * is wired up is a config choice (`FraudScorerProperties`/`FRAUD_SCORER_MODE`), never a Domain
 * concern.
 */
interface RefundFraudRiskScorer {
    fun score(features: RefundRiskFeatures): Double
}
