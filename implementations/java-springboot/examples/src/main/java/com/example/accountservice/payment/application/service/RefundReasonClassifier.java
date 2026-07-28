package com.example.accountservice.payment.application.service;

import com.example.accountservice.payment.domain.RefundReasonCategory;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) wrapping a self-hosted LLM
 * call — the same placement/shape as {@code TransactionAutoCategorizer}, just classifying a
 * refund's free-text reason instead of a transaction's merchant name. Ops-analytics input only (see
 * {@code RefundReasonInsightsQuery}) — this Technical Service is never invoked from, or its result
 * ever read by, {@code RequestRefundService}/{@code RefundEligibilityService}.
 */
public interface RefundReasonClassifier {

    RefundReasonCategory classify(String reason);
}
