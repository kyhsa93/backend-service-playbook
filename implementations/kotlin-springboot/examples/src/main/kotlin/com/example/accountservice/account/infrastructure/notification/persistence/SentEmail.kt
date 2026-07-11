package com.example.accountservice.account.infrastructure.notification.persistence

import com.example.accountservice.common.generateId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "sent_emails")
class SentEmail protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var sentEmailId: String = ""
        private set

    @Column(nullable = false)
    var accountId: String = ""
        private set

    @Column(nullable = false)
    var eventType: String = ""
        private set

    @Column(nullable = false)
    var recipient: String = ""
        private set

    @Column(nullable = false)
    var subject: String = ""
        private set

    @Column(nullable = false)
    var sesMessageId: String = ""
        private set

    @Column(nullable = false)
    var sentAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun create(
            accountId: String,
            eventType: String,
            recipient: String,
            subject: String,
            sesMessageId: String,
        ): SentEmail =
            SentEmail().apply {
                this.sentEmailId = generateId()
                this.accountId = accountId
                this.eventType = eventType
                this.recipient = recipient
                this.subject = subject
                this.sesMessageId = sesMessageId
                this.sentAt = LocalDateTime.now()
            }
    }
}
