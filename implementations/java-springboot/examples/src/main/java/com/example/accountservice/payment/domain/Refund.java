package com.example.accountservice.payment.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Refund Aggregate Root — a pure domain object. Refund itself cannot judge the original payment's
 * (Payment) status/amount — {@link RefundEligibilityService} (a Domain Service) loads both the
 * Payment and Refund Aggregates together, coordinates the judgment, and calls {@link #approve}/
 * {@link #reject} with the result ({@link RefundDecision}).
 */
public class Refund {

    private String refundId;
    private String paymentId;
    private long amount;
    private String reason;
    private RefundStatus status;
    private String decisionNote;
    private LocalDateTime createdAt;

    private final List<Object> domainEvents = new ArrayList<>();

    private Refund() {}

    public static Refund reconstitute(
            String refundId,
            String paymentId,
            long amount,
            String reason,
            RefundStatus status,
            String decisionNote,
            LocalDateTime createdAt) {
        Refund refund = new Refund();
        refund.refundId = refundId;
        refund.paymentId = paymentId;
        refund.amount = amount;
        refund.reason = reason;
        refund.status = status;
        refund.decisionNote = decisionNote;
        refund.createdAt = createdAt;
        return refund;
    }

    public static Refund create(String paymentId, long amount, String reason) {
        Refund refund = new Refund();
        refund.refundId = IdGenerator.generate();
        refund.paymentId = paymentId;
        refund.amount = amount;
        refund.reason = reason;
        refund.status = RefundStatus.REQUESTED;
        refund.createdAt = LocalDateTime.now();
        return refund;
    }

    /**
     * {@code accountId}/{@code ownerId} are not part of {@link RefundEligibilityService}'s judgment
     * — they are merely reference data the Application layer passes in, after the judgment, to
     * assemble the Integration Event that propagates to an external BC (they are not promoted to
     * fields of Refund itself).
     */
    public void approve(String accountId, String ownerId) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new PaymentException(
                    PaymentException.ErrorCode.REFUND_APPROVE_REQUIRES_REQUESTED_REFUND,
                    "Only a requested refund can be approved.");
        }
        this.status = RefundStatus.APPROVED;
        this.decisionNote = "The refund has been approved.";
        this.domainEvents.add(
                new RefundApprovedEvent(
                        this.refundId,
                        this.paymentId,
                        accountId,
                        ownerId,
                        this.amount,
                        LocalDateTime.now()));
    }

    public void reject(String reason) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new PaymentException(
                    PaymentException.ErrorCode.REFUND_REJECT_REQUIRES_REQUESTED_REFUND,
                    "Only a requested refund can be rejected.");
        }
        this.status = RefundStatus.REJECTED;
        this.decisionNote = reason;
    }

    /**
     * Currently, refund processing ends when the Account BC subscribes to refund.approved.v1 and
     * executes the credit — there is no callback path notifying Payment BC of that credit's success
     * (none exists on the REST surface). The method is kept for a complete Payment-domain state
     * model (verified by a Domain unit test), but no Command currently calls it — it remains
     * unwired for the same reason as {@link Payment#fail}.
     */
    public void complete() {
        if (this.status != RefundStatus.APPROVED) {
            throw new PaymentException(
                    PaymentException.ErrorCode.REFUND_COMPLETE_REQUIRES_APPROVED_REFUND,
                    "Only an approved refund can be marked as completed.");
        }
        this.status = RefundStatus.COMPLETED;
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public String getRefundId() {
        return refundId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
