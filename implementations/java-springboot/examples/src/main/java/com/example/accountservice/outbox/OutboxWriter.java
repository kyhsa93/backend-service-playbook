package com.example.accountservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Saves domain events collected by an Aggregate into the Outbox table. {@link
 * com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl#save} calls
 * this inside the same physical transaction as the Aggregate save, so that event publication
 * commits atomically together with the Aggregate's state change.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void saveAll(List<Object> events) {
        if (events.isEmpty()) {
            return;
        }
        List<OutboxEvent> outboxEvents = events.stream().map(this::toOutboxEvent).toList();
        outboxJpaRepository.saveAll(outboxEvents);
    }

    /**
     * Loads a single event into the Outbox with an explicit eventType. An Integration Event exposed
     * to an external BC must use a versioned public contract name (e.g. {@code
     * account.suspended.v1}) instead of the class name as its eventType, so unlike {@link
     * #saveAll(List)} for domain events, this takes the type directly. Called by an
     * application/event handler when it converts a received Domain Event into an Integration Event.
     */
    public void save(String eventType, Object payload) {
        try {
            outboxJpaRepository.save(
                    OutboxEvent.create(eventType, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event: " + eventType, e);
        }
    }

    private OutboxEvent toOutboxEvent(Object event) {
        try {
            return OutboxEvent.create(
                    event.getClass().getSimpleName(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize event: " + event.getClass().getSimpleName(), e);
        }
    }
}
