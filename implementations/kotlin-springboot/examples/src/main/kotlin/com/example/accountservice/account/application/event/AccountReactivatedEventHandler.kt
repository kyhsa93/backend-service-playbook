package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Sends an account-reactivation notification email when AccountReactivatedEvent is delivered via the
 * Outbox.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 *
 * [eventId] is the `eventId` of the Outbox row this event was stored in — [NotificationService] uses it
 * as the key to apply Level 2 (Ledger) duplicate-send prevention.
 */
@Component
class AccountReactivatedEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: AccountReactivatedEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountReactivated",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Your account has been reactivated",
            body = "Account (${event.accountId}) has been reactivated.",
        )
    }
}
