package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.InterestPaidEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Sends an interest-payment notification email when InterestPaidEvent is delivered via the Outbox — the
 * same path as the other 5 Account Domain Event Handlers (MoneyDepositedEventHandler, etc.). This event
 * is published only when `PayInterestService` actually credits interest (only when the amount is
 * non-zero), so an account that was skipped because its interest was zero also gets no email.
 *
 * Exceptions are not caught and are thrown as-is — [com.example.accountservice.outbox.OutboxConsumer]
 * catches them, logs, and leaves the Outbox row as processed=false so the next call retries it
 * (at-least-once delivery).
 */
@Component
class InterestPaidEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: InterestPaidEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "InterestPaid",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Interest has been paid",
            body =
                "Interest of ${event.amount.amount} ${event.amount.currency} has been paid. " +
                    "Balance after payment: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
        )
    }
}
