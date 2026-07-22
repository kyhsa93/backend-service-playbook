package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Sends a withdrawal-completed notification email when MoneyWithdrawnEvent is delivered via the Outbox.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 *
 * [eventId] is the `eventId` of the Outbox row this event was stored in — [NotificationService] uses it
 * as the key to apply Level 2 (Ledger) duplicate-send prevention.
 */
@Component
class MoneyWithdrawnEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: MoneyWithdrawnEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "MoneyWithdrawn",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Your withdrawal is complete",
            body =
                "${event.amount.amount} ${event.amount.currency} has been withdrawn. " +
                    "Balance after withdrawal: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
        )
    }
}
