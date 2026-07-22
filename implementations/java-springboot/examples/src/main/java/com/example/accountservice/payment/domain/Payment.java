package com.example.accountservice.payment.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Payment Aggregate Root — a pure domain object. It does not depend on any framework/ORM.
 * Persistence mapping is handled entirely by infrastructure/persistence/PaymentJpaEntity +
 * PaymentMapper (the same domain/JPA separation as account/domain/Account.java).
 *
 * <p>It only references which card was used via {@code cardId} and which account is debited via
 * {@code accountId} (no FK crossing a BC boundary) — the Application layer finishes checking the
 * card/account's actual status and balance via {@code CardAdapter}/{@code AccountAdapter} (ACL)
 * synchronously, before this Aggregate is even created. Payment itself does not know "is the card
 * active" or "is the balance sufficient."
 */
public class Payment {

    private String paymentId;
    private String cardId;
    private String accountId;
    private String ownerId;
    private long amount;
    private PaymentStatus status;
    private LocalDateTime createdAt;

    private final List<Object> domainEvents = new ArrayList<>();

    private Payment() {}

    /**
     * Used by the Repository implementation to restore a Payment from persisted data. It
     * reconstructs a state that was already committed in the past and does not generate Domain
     * Events.
     */
    public static Payment reconstitute(
            String paymentId,
            String cardId,
            String accountId,
            String ownerId,
            long amount,
            PaymentStatus status,
            LocalDateTime createdAt) {
        Payment payment = new Payment();
        payment.paymentId = paymentId;
        payment.cardId = cardId;
        payment.accountId = accountId;
        payment.ownerId = ownerId;
        payment.amount = amount;
        payment.status = status;
        payment.createdAt = createdAt;
        return payment;
    }

    /**
     * A pure creation factory called only after the Application layer's synchronous Adapter calls
     * have already determined whether the card is active and the account balance is sufficient — it
     * only creates as PENDING, with no event.
     */
    public static Payment create(String cardId, String accountId, String ownerId, long amount) {
        Payment payment = new Payment();
        payment.paymentId = IdGenerator.generate();
        payment.cardId = cardId;
        payment.accountId = accountId;
        payment.ownerId = ownerId;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = LocalDateTime.now();
        return payment;
    }

    public void complete() {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT,
                    "Only a pending payment can be marked as completed.");
        }
        this.status = PaymentStatus.COMPLETED;
        this.domainEvents.add(
                new PaymentCompletedEvent(
                        this.paymentId,
                        this.cardId,
                        this.accountId,
                        this.ownerId,
                        this.amount,
                        LocalDateTime.now()));
    }

    /**
     * Currently {@code CreatePaymentService} determines pass/fail via a synchronous Adapter before
     * creation, so there is no path where a Payment Aggregate is created as PENDING and then fails.
     * However, to prepare for a future scenario where failure arrives asynchronously (e.g. a
     * payment gateway callback), the Aggregate itself holds the state transition (verified by a
     * Domain unit test) — no Command currently calls this method.
     */
    public void fail(String reason) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT,
                    "Only a pending payment can be marked as failed.");
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * Cancelling a payment reverses an already-confirmed (COMPLETED) payment, so it is only
     * possible from COMPLETED.
     */
    public void cancel(String reason) {
        if (this.status != PaymentStatus.COMPLETED) {
            throw new PaymentException(
                    PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT,
                    "Only a completed payment can be cancelled.");
        }
        this.status = PaymentStatus.CANCELLED;
        this.domainEvents.add(
                new PaymentCancelledEvent(
                        this.paymentId,
                        this.accountId,
                        this.ownerId,
                        this.amount,
                        reason,
                        LocalDateTime.now()));
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getCardId() {
        return cardId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
