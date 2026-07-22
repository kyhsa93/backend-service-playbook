package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCancelledEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Receives the internal Domain Event (PaymentCancelledEvent), converts it into the Integration Event
 * for external BCs (payment.cancelled.v1), and loads it into the Outbox. Account BC subscribes to this
 * and executes the compensating credit (deposit) — a compensating transaction that reverses the
 * already-deducted amount.
 */
@Component
class PaymentCancelledEventHandler(
    private val outboxWriter: OutboxWriter,
) {
    private val logger = LoggerFactory.getLogger(PaymentCancelledEventHandler::class.java)

    fun handle(event: PaymentCancelledEvent) {
        logger
            .atInfo()
            .addKeyValue("payment_id", event.paymentId)
            .addKeyValue("account_id", event.accountId)
            .addKeyValue("reason", event.reason)
            .log("Payment cancelled")

        outboxWriter.saveAll(
            listOf(
                PaymentCancelledIntegrationEventV1(
                    paymentId = event.paymentId,
                    accountId = event.accountId,
                    amount = event.amount,
                    cancelledAt = event.cancelledAt.toString(),
                ),
            ),
        )
    }
}
