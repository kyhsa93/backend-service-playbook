package com.example.accountservice.payment.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * Payment BC가 외부 BC(Account)에 공개하는 Integration Event(공개 계약).
 * Account가 보상 크레딧(deposit)을 실행하는 데 필요한 최소 정보만 싣는다.
 */
data class PaymentCancelledIntegrationEventV1(
    val paymentId: String,
    val accountId: String,
    val amount: Long,
    val cancelledAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "payment.cancelled.v1"
    }
}
