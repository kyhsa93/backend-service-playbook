package com.example.accountservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An Outbox record that persists a Domain Event in the same transaction as the Aggregate save. It
 * stores a domain event (a record) from account.domain, serialized to JSON — {@link OutboxPoller}
 * later reads this table, publishes to SQS, and marks processed as true. The actual handler
 * execution only happens afterward, when {@link OutboxConsumer} receives it from SQS.
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
