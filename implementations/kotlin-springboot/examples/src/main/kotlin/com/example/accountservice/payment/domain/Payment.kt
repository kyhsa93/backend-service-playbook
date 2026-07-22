package com.example.accountservice.payment.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Payment Aggregate Root — a pure Kotlin object with no dependency on any framework/ORM
 * (the same domain/JPA separation structure as account/domain/Account.kt, card/domain/Card.kt).
 *
 * It only references which card/account is involved via `cardId`/`accountId` (no FK crossing the BC
 * boundary). Payment cannot decide for itself whether the card is active or whether the linked
 * account has sufficient balance — the Application layer synchronously queries
 * [com.example.accountservice.payment.application.adapter.CardAdapter]/
 * [com.example.accountservice.payment.application.adapter.AccountAdapter] (ACL) and finishes that
 * judgment before calling [create].
 */
class Payment private constructor() {
    var paymentId: String = ""
        private set

    var cardId: String = ""
        private set

    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var amount: Long = 0
        private set

    var status: PaymentStatus = PaymentStatus.PENDING
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    private val domainEvents: MutableList<PaymentDomainEvent> = mutableListOf()

    companion object {
        /**
         * A pure creation factory called after the Application layer's synchronous Adapter call has
         * already finished judging whether the card is active and whether the account balance is
         * sufficient — it only creates in the PENDING state and raises no event.
         */
        fun create(
            cardId: String,
            accountId: String,
            ownerId: String,
            amount: Long,
        ): Payment =
            Payment().apply {
                this.paymentId = generateId()
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.amount = amount
                this.status = PaymentStatus.PENDING
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Used by the Repository implementation to restore a Payment from persisted data (e.g. a JPA
         * entity). Unlike create(), it does not raise a domain event.
         */
        fun reconstitute(
            paymentId: String,
            cardId: String,
            accountId: String,
            ownerId: String,
            amount: Long,
            status: PaymentStatus,
            createdAt: LocalDateTime,
        ): Payment =
            Payment().apply {
                this.paymentId = paymentId
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.amount = amount
                this.status = status
                this.createdAt = createdAt
            }
    }

    fun complete() {
        if (status != PaymentStatus.PENDING) throw PaymentCompleteRequiresPendingPaymentException()
        status = PaymentStatus.COMPLETED
        domainEvents += PaymentCompletedEvent(paymentId, cardId, accountId, ownerId, amount, LocalDateTime.now())
    }

    /**
     * [com.example.accountservice.payment.application.command.CreatePaymentService] judges whether
     * payment is possible via a synchronous Adapter call before the Payment is created, so there is
     * no path where a Payment is created as PENDING and then fails. Nonetheless, the Aggregate itself
     * owns this state transition (verified by a Domain unit test) to prepare for a future scenario
     * where failure arrives asynchronously, such as a payment gateway callback — no Command calls it
     * yet.
     */
    fun fail(reason: String) {
        if (status != PaymentStatus.PENDING) throw PaymentFailRequiresPendingPaymentException()
        status = PaymentStatus.FAILED
        // reason is not separately stored on the current Payment state (same as the nestjs reference)
        // — this is an unwired domain method whose purpose is the state transition itself.
    }

    /** Cancelling a payment reverts an already-confirmed (COMPLETED) payment, so it is only possible from COMPLETED. */
    fun cancel(reason: String) {
        if (status != PaymentStatus.COMPLETED) throw PaymentCancelRequiresCompletedPaymentException()
        status = PaymentStatus.CANCELLED
        domainEvents += PaymentCancelledEvent(paymentId, accountId, ownerId, amount, reason, LocalDateTime.now())
    }

    fun pullDomainEvents(): List<PaymentDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
}
