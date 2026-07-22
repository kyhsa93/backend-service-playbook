package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.RefundApprovedIntegrationEventV1
import com.example.accountservice.payment.domain.RefundApprovedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Receives the internal Domain Event (RefundApprovedEvent), converts it into the Integration Event
 * for external BCs (refund.approved.v1), and loads it into the Outbox. Account BC subscribes to this
 * and executes the refund credit (deposit).
 */
@Component
class RefundApprovedEventHandler(
    private val outboxWriter: OutboxWriter,
) {
    private val logger = LoggerFactory.getLogger(RefundApprovedEventHandler::class.java)

    fun handle(event: RefundApprovedEvent) {
        logger
            .atInfo()
            .addKeyValue("refund_id", event.refundId)
            .addKeyValue("payment_id", event.paymentId)
            .addKeyValue("account_id", event.accountId)
            .log("Refund approved")

        outboxWriter.saveAll(
            listOf(
                RefundApprovedIntegrationEventV1(
                    refundId = event.refundId,
                    paymentId = event.paymentId,
                    accountId = event.accountId,
                    amount = event.amount,
                    approvedAt = event.approvedAt.toString(),
                ),
            ),
        )
    }
}
