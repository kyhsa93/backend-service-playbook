package com.example.accountservice.card.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Account BC가 발행한 Integration Event(account.suspended.v1 / account.closed.v1)의 페이로드 중
 * Card BC가 필요로 하는 최소 필드만 담는 로컬 뷰. Account BC의 Integration Event 클래스를 직접
 * import하지 않고(공개 계약은 이벤트 타입 문자열 + JSON 스키마다), 필요한 {@code accountId}만 읽는다.
 * {@code suspendedAt}/{@code closedAt} 등 나머지 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountIntegrationEventPayload(String accountId) {}
