package com.example.accountservice.payment.domain;

/**
 * The feature vector {@code RefundFraudRiskScorer} (a Technical Service, see
 * application/service/RefundFraudRiskScorer.java) scores. Assembled by the Application layer from
 * the requester's refund history ({@link RefundRepository#summarizeRefundsByOwner}) plus the
 * current Payment/Refund pair — never by {@link RefundEligibilityService} (the Domain Service) or
 * the scorer itself, so both stay decoupled from how history is stored/queried.
 */
public record RefundRiskFeatures(
        long refundCountLast30Days,
        long rejectedRefundCountLast30Days,
        double refundToPaymentAmountRatio,
        double minutesSincePayment) {}
