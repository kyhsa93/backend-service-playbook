package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

/**
 * Published unconditionally by {@link Refund#create} — before {@link RefundEligibilityService}'s
 * approve/reject judgment even runs. {@code ClassifyRefundReasonEventHandler} reacts to this to
 * build ops-analytics insight from every refund's stated reason, independent of whether the refund
 * is ultimately approved or rejected (a rejected refund's reason is just as useful a signal for the
 * ops dashboard as an approved one's).
 */
public record RefundRequestedEvent(
        String refundId, String paymentId, String reason, LocalDateTime createdAt) {}
