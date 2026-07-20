package com.example.accountservice.outbox

/**
 * 외부 BC에 공개하는 Integration Event(공개 계약)가 구현하는 표식 인터페이스.
 *
 * Domain Event는 클래스명을 그대로 Outbox row의 eventType으로 쓰지만, Integration Event는 버전이
 * 명시된 공개 계약명([eventName], 예: `account.suspended.v1`)을 eventType으로 쓴다 —
 * [OutboxEvent.from]이 이 인터페이스 구현 여부로 둘을 구분한다. eventName 리터럴이 곧
 * [EventHandlerRegistry]의 라우팅 키(SQS `MessageAttributes.eventType`)가 된다.
 */
interface IntegrationEventContract {
    val eventName: String
}
