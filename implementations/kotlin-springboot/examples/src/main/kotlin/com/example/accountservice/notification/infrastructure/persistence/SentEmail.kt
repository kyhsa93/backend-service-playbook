package com.example.accountservice.notification.infrastructure.persistence

import com.example.accountservice.common.generateId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * The Level 2 (Ledger) idempotency key is the composite (sourceEventId, eventType), not
 * sourceEventId alone — one Outbox delivery (one sourceEventId) can legitimately result in more
 * than one distinct email when more than one Handler subscribes to that eventType (see
 * DetectWithdrawalAnomalyEventHandler, the first case of this: MoneyWithdrawnEvent's
 * eventId is shared by both MoneyWithdrawnEventHandler's "MoneyWithdrawn" email and this
 * handler's "WithdrawalAnomalyDetected" alert). A retried delivery of the same handler still
 * collides on the same (sourceEventId, eventType) pair, so it's still deduped correctly.
 */
@Entity
@Table(
    name = "sent_emails",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_sent_emails_source_event_id_event_type",
            columnNames = ["source_event_id", "event_type"],
        ),
    ],
)
class SentEmail protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, unique = true)
    var sentEmailId: String = ""
        protected set

    @Column(nullable = false)
    var accountId: String = ""
        protected set

    @Column(nullable = false)
    var eventType: String = ""
        protected set

    @Column(name = "source_event_id", nullable = false)
    var sourceEventId: String = ""
        protected set

    @Column(nullable = false)
    var recipient: String = ""
        protected set

    @Column(nullable = false)
    var subject: String = ""
        protected set

    @Column(nullable = false)
    var sesMessageId: String = ""
        protected set

    @Column(nullable = false)
    var sentAt: LocalDateTime = LocalDateTime.now()
        protected set

    companion object {
        fun create(
            accountId: String,
            eventType: String,
            sourceEventId: String,
            recipient: String,
            subject: String,
            sesMessageId: String,
        ): SentEmail =
            SentEmail().apply {
                this.sentEmailId = generateId()
                this.accountId = accountId
                this.eventType = eventType
                this.sourceEventId = sourceEventId
                this.recipient = recipient
                this.subject = subject
                this.sesMessageId = sesMessageId
                this.sentAt = LocalDateTime.now()
            }
    }
}
