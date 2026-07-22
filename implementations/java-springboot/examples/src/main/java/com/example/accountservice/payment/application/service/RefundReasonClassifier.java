package com.example.accountservice.payment.application.service;

import com.example.accountservice.payment.domain.RefundReasonClassification;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) abstracting an LLM call that
 * classifies a refund's free-text reason. {@code RefundEligibilityService} (a Domain Service) never
 * depends on this interface and never calls an LLM itself — it only ever receives the
 * already-computed {@link RefundReasonClassification} as a plain value and makes its own pure
 * judgment from its fields. This keeps the "LLM call = Infrastructure, judgment = Domain" boundary
 * intact: the classifier can only ever supply one more signal to weigh, never decide the outcome
 * itself.
 */
public interface RefundReasonClassifier {

    RefundReasonClassification classify(String reason);
}
