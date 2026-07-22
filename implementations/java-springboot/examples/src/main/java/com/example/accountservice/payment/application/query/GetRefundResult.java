package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Refund;
import java.time.LocalDateTime;

/**
 * The result shape reused by both refund requests (RequestRefundService) and single/list lookups
 * (GetRefundsService) — for the same reason as {@link GetPaymentResult}.
 */
public record GetRefundResult(
        String refundId,
        String paymentId,
        long amount,
        String reason,
        String status,
        String decisionNote,
        LocalDateTime createdAt) {

    public static GetRefundResult from(Refund refund) {
        return new GetRefundResult(
                refund.getRefundId(),
                refund.getPaymentId(),
                refund.getAmount(),
                refund.getReason(),
                refund.getStatus().name(),
                refund.getDecisionNote(),
                refund.getCreatedAt());
    }
}
