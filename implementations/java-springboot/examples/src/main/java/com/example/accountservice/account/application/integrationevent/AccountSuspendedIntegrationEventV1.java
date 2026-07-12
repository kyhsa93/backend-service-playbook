package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

/**
 * Account BC가 외부 BC에 공개하는 Integration Event (공개 계약).
 * 내부 Domain Event({@code AccountSuspendedEvent})와 분리해 이름·스키마를 안정적으로 유지하고
 * 버전({@link #EVENT_TYPE})을 명시한다. {@code EVENT_TYPE} 리터럴이 Outbox row의 eventType이 되며,
 * 수신 측(Card BC)은 이 클래스를 import하지 않고 같은 문자열 계약으로만 구독한다.
 */
public record AccountSuspendedIntegrationEventV1(String accountId, LocalDateTime suspendedAt) {

    public static final String EVENT_TYPE = "account.suspended.v1";
}
