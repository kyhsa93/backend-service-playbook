package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * The internal Domain Event published when a refund is approved. {@code accountId}/{@code ownerId}
 * are not Refund's own fields — when {@link Refund#approve} is called, these values are passed in
 * from the original payment (Payment) that the Application layer has already loaded, and are simply
 * carried in this event. The Account BC subscribes to this event series (refund.approved.v1) and
 * runs the refund credit (deposit).
 */
public record RefundApprovedEvent(
        String refundId,
        String paymentId,
        String accountId,
        String ownerId,
        long amount,
        LocalDateTime approvedAt) {}
