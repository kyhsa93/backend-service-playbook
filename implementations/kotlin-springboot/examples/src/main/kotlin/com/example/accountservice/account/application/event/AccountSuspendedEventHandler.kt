package com.example.accountservice.account.application.event

import com.example.accountservice.account.application.integrationevent.AccountSuspendedIntegrationEventV1
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.notification.application.service.NotificationService
import com.example.accountservice.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Sends an account-suspension notification email when AccountSuspendedEvent is delivered via the Outbox,
 * and writes an Integration Event (account.suspended.v1) notifying external BCs (Card, etc.) into the
 * Outbox.
 *
 * The EventHandler in application/event/ is the one exception allowed to use [OutboxWriter] directly, and
 * this is where the internal Domain Event is converted into an Integration Event for external BCs (an
 * Aggregate never builds an Integration Event directly — the conversion point is always the
 * EventHandler). This handler itself is the callback that
 * [com.example.accountservice.outbox.OutboxConsumer] invokes when it receives AccountSuspendedEvent from
 * SQS — the Integration Event written here is not processed immediately within the same call; it is
 * published to SQS at the next [com.example.accountservice.outbox.OutboxPoller] tick (at most 1 second
 * later) and consumed separately (with the async transition, "immediate re-drain within the same
 * transaction" no longer holds).
 */
@Component
class AccountSuspendedEventHandler(
    private val notificationService: NotificationService,
    private val outboxWriter: OutboxWriter,
) {
    private val logger = LoggerFactory.getLogger(AccountSuspendedEventHandler::class.java)

    fun handle(
        event: AccountSuspendedEvent,
        eventId: String,
    ) {
        // Writes an Integration Event notifying external BCs (Card, etc.) into the Outbox.
        outboxWriter.saveAll(
            listOf(AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt.toString())),
        )

        // The notification is best-effort. This handler is not allowed to throw on failure — if it did,
        // this Outbox row (AccountSuspendedEvent) itself would remain marked as failed and get
        // re-drained on the next call, and in that process the Integration Event already written above
        // would be written again (harmless since the receiving side is idempotent, but an unnecessary
        // amplification). Retrying the notification itself is the responsibility of a separate outbox
        // row (the sent_email pipeline). eventId is the eventId of this Outbox row — NotificationService
        // uses it as the key to apply Level 2 (Ledger) duplicate-send prevention.
        try {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountSuspended",
                sourceEventId = eventId,
                recipient = event.email,
                subject = "[Account] Your account has been suspended",
                body = "Account (${event.accountId}) has been suspended.",
            )
        } catch (e: Exception) {
            logger
                .atError()
                .addKeyValue("account_id", event.accountId)
                .setCause(e)
                .log("Failed to send suspension notification")
        }
    }
}
