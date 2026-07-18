package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Refund;
import java.time.LocalDateTime;

/**
 * 환불 요청(RequestRefundService)과 단건/목록 조회(GetRefundsService)가 모두 재사용하는 결과 형태 — {@link
 * GetPaymentResult}와 동일한 이유.
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
