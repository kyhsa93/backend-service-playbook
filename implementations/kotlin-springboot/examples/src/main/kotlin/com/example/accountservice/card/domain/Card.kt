package com.example.accountservice.card.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Card Aggregate Root — a pure Kotlin object with no dependency on any framework/ORM.
 * Persistence mapping is handled exclusively by infrastructure/persistence/CardJpaEntity + CardMapper
 * (the same domain/JPA separation structure as account/domain/Account.kt).
 */
class Card private constructor() {
    var cardId: String = ""
        private set

    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var brand: String = ""
        private set

    var status: CardStatus = CardStatus.ACTIVE
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    // This is the Level 1 (intrinsic idempotency) key for the monthly card-statement delivery
    // (SendMonthlyCardStatementsService) — because the Card itself knows whether "this month's
    // statement has already been sent," the second invocation is skipped even if the Task runs twice
    // in the same month under at-least-once delivery. Stored as a "yyyy-MM" format string
    // (java.time.YearMonth.toString()) — the same design as account/domain/Account.kt's
    // lastInterestPaidAt (LocalDate).
    var lastStatementSentMonth: String? = null
        private set

    companion object {
        /**
         * Issues a new card — it always starts in the ACTIVE state.
         *
         * The Card Aggregate has no way of knowing whether the linked account is active. Whether
         * issuance is allowed (account exists and is active) is determined by the Application layer
         * via a synchronous query through AccountAdapter (ACL) before this factory is called.
         */
        fun issue(
            accountId: String,
            ownerId: String,
            brand: String,
        ): Card =
            Card().apply {
                this.cardId = generateId()
                this.accountId = accountId
                this.ownerId = ownerId
                this.brand = brand
                this.status = CardStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Used by the Repository implementation to restore a Card from persisted data (a JPA
         * entity, etc.). Unlike issue(), it does not create a new identifier/timestamp — it simply
         * reconstructs the stored state as-is.
         */
        fun reconstitute(
            cardId: String,
            accountId: String,
            ownerId: String,
            brand: String,
            status: CardStatus,
            createdAt: LocalDateTime,
            lastStatementSentMonth: String? = null,
        ): Card =
            Card().apply {
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.brand = brand
                this.status = status
                this.createdAt = createdAt
                this.lastStatementSentMonth = lastStatementSentMonth
            }
    }

    fun suspend() {
        if (status == CardStatus.CANCELLED) throw CancelledCardCannotBeSuspendedException()
        if (status == CardStatus.SUSPENDED) throw CardAlreadySuspendedException()
        status = CardStatus.SUSPENDED
    }

    fun cancel() {
        if (status == CardStatus.CANCELLED) throw CardAlreadyCancelledException()
        status = CardStatus.CANCELLED
    }

    /**
     * Records that the usage statement for [yearMonth] ("yyyy-MM") has been sent. Unlike
     * suspend()/cancel(), this is a plain record rather than a state-machine transition, so there is
     * no invariant to violate — recording the same month twice (a retry) produces the same result, so
     * no exception is thrown (scheduling.md Level 1 idempotency). The caller
     * (SendMonthlyCardStatementsService) filters out already-sent cards at the query step using
     * `CardFindQuery.excludeStatementMonth` before calling this.
     */
    fun markStatementSent(yearMonth: String) {
        lastStatementSentMonth = yearMonth
    }
}
