package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Payment;
import java.time.LocalDateTime;

/**
 * The Payment query result — both payment creation (CreatePaymentService) and single/list lookups
 * (GetPaymentService) reuse this shape as-is (just like the nestjs implementation's {@code
 * CreatePaymentResponseBody extends GetPaymentResult}: since Payment has no Value Object conversion
 * such as Money, the two use cases are structurally identical, so a separate CreatePaymentResult is
 * not introduced).
 */
public record GetPaymentResult(
        String paymentId,
        String cardId,
        String accountId,
        String ownerId,
        long amount,
        String status,
        LocalDateTime createdAt) {

    public static GetPaymentResult from(Payment payment) {
        return new GetPaymentResult(
                payment.getPaymentId(),
                payment.getCardId(),
                payment.getAccountId(),
                payment.getOwnerId(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getCreatedAt());
    }
}
