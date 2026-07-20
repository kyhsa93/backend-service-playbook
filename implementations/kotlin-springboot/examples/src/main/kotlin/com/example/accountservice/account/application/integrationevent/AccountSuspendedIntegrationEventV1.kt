package com.example.accountservice.account.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * Account BC가 외부 BC(Card 등)에 공개하는 Integration Event (공개 계약).
 *
 * 내부 Domain Event([com.example.accountservice.account.domain.AccountSuspendedEvent])와 분리해
 * 이름·스키마를 안정적으로 유지하고 버전을 명시한다. [eventName] 리터럴이 Outbox row의 eventType으로
 * 사용되어 [com.example.accountservice.outbox.EventHandlerRegistry]의 라우팅 키가 된다.
 */
data class AccountSuspendedIntegrationEventV1(
    val accountId: String,
    val suspendedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "account.suspended.v1"
    }
}
