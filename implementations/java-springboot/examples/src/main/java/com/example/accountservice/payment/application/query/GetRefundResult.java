package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Refund;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * The result shape reused by both refund requests (RequestRefundService) and single/list lookups
 * (GetRefundsService) — for the same reason as {@link GetPaymentResult}.
 */
public record GetRefundResult(
        @Schema(description = "The generated refund ID.") String refundId,
        @Schema(description = "The paymentId this refund was requested against.") String paymentId,
        @Schema(description = "The requested refund amount.") long amount,
        @Schema(description = "Why the refund was requested.") String reason,
        @Schema(
                        description =
                                "The refund's status. A refund can be `REJECTED` as a valid business"
                                        + " outcome, not just approved — see the `requestRefund` operation.",
                        example = "APPROVED")
                String status,
        @Schema(description = "A human-readable note explaining the approve/reject decision.")
                String decisionNote,
        @Schema(description = "When the refund was requested.") LocalDateTime createdAt) {

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
