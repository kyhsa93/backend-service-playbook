package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Sends an account-creation notification email when AccountCreatedEvent is delivered via the Outbox.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 *
 * [eventId] is the `eventId` of the Outbox row this event was stored in — [NotificationService] uses it
 * as the key to apply Level 2 (Ledger) duplicate-send prevention.
 */
@Component
class AccountCreatedEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: AccountCreatedEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountCreated",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Your account has been opened",
            body = "Account (${event.accountId}) has been opened. Currency: ${event.currency}",
        )
    }
}
