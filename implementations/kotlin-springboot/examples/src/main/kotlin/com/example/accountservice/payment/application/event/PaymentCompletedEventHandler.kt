package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The Application EventHandler that receives the internal Domain Event (PaymentCompletedEvent),
 * converts it into the Integration Event for external BCs (payment.completed.v1), and loads it into
 * the Outbox. Account BC subscribes to this Integration Event and performs the actual deduction
 * (withdraw).
 */
@Component
class PaymentCompletedEventHandler(
    private val outboxWriter: OutboxWriter,
) {
    private val logger = LoggerFactory.getLogger(PaymentCompletedEventHandler::class.java)

    fun handle(event: PaymentCompletedEvent) {
        logger
            .atInfo()
            .addKeyValue("payment_id", event.paymentId)
            .addKeyValue("account_id", event.accountId)
            .log("Payment completed")

        outboxWriter.saveAll(
            listOf(
                PaymentCompletedIntegrationEventV1(
                    paymentId = event.paymentId,
                    accountId = event.accountId,
                    amount = event.amount,
                    completedAt = event.completedAt.toString(),
                ),
            ),
        )
    }
}
