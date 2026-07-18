package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCancelledEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 내부 Domain Event(PaymentCancelledEvent)를 수신해 외부 BC용 Integration Event
 * (payment.cancelled.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해 보상 크레딧
 * (deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
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
            .log("결제 취소됨")

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
