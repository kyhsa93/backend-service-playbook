package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.RefundApprovedIntegrationEventV1
import com.example.accountservice.payment.domain.RefundApprovedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 내부 Domain Event(RefundApprovedEvent)를 수신해 외부 BC용 Integration Event
 * (refund.approved.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해 환불 크레딧
 * (deposit)을 실행한다.
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
            .log("환불 승인됨")

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
