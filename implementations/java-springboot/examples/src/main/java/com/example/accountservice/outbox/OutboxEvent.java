package com.example.accountservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain Event를 Aggregate 저장과 같은 트랜잭션에서 영속화하는 Outbox 레코드. account.domain의 도메인 이벤트(record)를 JSON으로
 * 직렬화해 보관하며, {@link OutboxPoller}가 이후 이 테이블을 읽어 SQS로 발행하고 processed를 true로 표시한다 — 그 이후 실제 핸들러 실행은
 * {@link OutboxConsumer}가 SQS에서 수신했을 때 이뤄진다.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @Column(length = 32, nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OutboxEvent() {}

    public static OutboxEvent create(String eventType, String payload) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.eventId = UUID.randomUUID().toString().replace("-", "");
        outboxEvent.eventType = eventType;
        outboxEvent.payload = payload;
        outboxEvent.processed = false;
        outboxEvent.createdAt = LocalDateTime.now();
        return outboxEvent;
    }

    public void markProcessed() {
        this.processed = true;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isProcessed() {
        return processed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
