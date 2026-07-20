package com.example.accountservice.outbox;

/**
 * Outbox 테이블에 쌓인 도메인 이벤트 한 건을 처리하는 핸들러. {@link #eventType()}은 이벤트를 발행한 도메인 이벤트 record의 simple
 * name(예: {@code AccountCreatedEvent})과 일치해야 {@link OutboxConsumer}가 올바른 핸들러로 라우팅한다.
 */
public interface OutboxEventHandler {

    String eventType();

    void handle(String payload) throws Exception;
}
