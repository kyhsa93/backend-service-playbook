package com.example.accountservice.notification.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SentEmailJpaRepository : JpaRepository<SentEmail, Long> {
    fun findByAccountId(accountId: String): List<SentEmail>

    fun findBySesMessageId(sesMessageId: String): SentEmail?

    /**
     * Level 2 (Ledger) idempotency check — verifies whether this specific (Outbox event, eventType)
     * pair has already resulted in an email being sent. Scoped to the pair rather than sourceEventId
     * alone, since more than one Handler may subscribe to the same eventType and each legitimately
     * sends its own distinct email for the same Outbox delivery (see SentEmail's class doc).
     */
    fun existsBySourceEventIdAndEventType(
        sourceEventId: String,
        eventType: String,
    ): Boolean
}
