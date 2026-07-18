package com.example.accountservice.payment.application.event

import com.example.accountservice.outbox.OutboxWriter
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 내부 Domain Event(PaymentCompletedEvent)를 수신해 외부 BC용 Integration Event
 * (payment.completed.v1)로 변환해 Outbox에 적재하는 Application EventHandler.
 * Account BC가 이 Integration Event를 구독해 실제 차감(withdraw)을 수행한다.
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
            .log("결제 완료됨")

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
