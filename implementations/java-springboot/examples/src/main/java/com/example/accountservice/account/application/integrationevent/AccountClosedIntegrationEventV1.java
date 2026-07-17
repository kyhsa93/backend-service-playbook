package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

/**
 * Account BC가 외부 BC에 공개하는 Integration Event (공개 계약). 내부 Domain Event({@code AccountClosedEvent})와
 * 분리해 버전({@link #EVENT_TYPE})을 명시한다.
 */
public record AccountClosedIntegrationEventV1(String accountId, LocalDateTime closedAt) {

    public static final String EVENT_TYPE = "account.closed.v1";
}
