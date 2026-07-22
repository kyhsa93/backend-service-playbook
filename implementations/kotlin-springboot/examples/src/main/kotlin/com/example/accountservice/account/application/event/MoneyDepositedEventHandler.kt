package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.MoneyDepositedEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Sends a deposit-completed notification email when MoneyDepositedEvent is delivered via the Outbox.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 *
 * [eventId] is the `eventId` of the Outbox row this event was stored in — [NotificationService] uses it
 * as the key to apply Level 2 (Ledger) duplicate-send prevention.
 */
@Component
class MoneyDepositedEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: MoneyDepositedEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "MoneyDeposited",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Your deposit is complete",
            body =
                "${event.amount.amount} ${event.amount.currency} has been deposited. " +
                    "Balance after deposit: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
        )
    }
}
