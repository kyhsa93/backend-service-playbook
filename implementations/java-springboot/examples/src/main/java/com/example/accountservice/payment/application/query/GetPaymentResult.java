package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * The Payment query result — both payment creation (CreatePaymentService) and single/list lookups
 * (GetPaymentService) reuse this shape as-is (just like the nestjs implementation's {@code
 * CreatePaymentResponseBody extends GetPaymentResult}: since Payment has no Value Object conversion
 * such as Money, the two use cases are structurally identical, so a separate CreatePaymentResult is
 * not introduced).
 */
public record GetPaymentResult(
        @Schema(description = "The generated payment ID.") String paymentId,
        @Schema(description = "The cardId charged for this payment.") String cardId,
        @Schema(description = "The accountId debited for this payment.") String accountId,
        @Schema(description = "The userId of the payment's owner.") String ownerId,
        @Schema(description = "The payment amount, in the smallest unit of the account's currency.")
                long amount,
        @Schema(description = "The payment's status.", example = "COMPLETED") String status,
        @Schema(description = "When the payment was created.") LocalDateTime createdAt) {

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
