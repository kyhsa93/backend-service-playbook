package com.example.accountservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Tracer tracer;
    private final Propagator propagator;

    public void saveAll(List<Object> events) {
        if (events.isEmpty()) {
            return;
        }
        String traceparent = currentTraceparent();
        List<OutboxEvent> outboxEvents =
                events.stream().map(event -> toOutboxEvent(event, traceparent)).toList();
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
                    OutboxEvent.create(
                            eventType,
                            objectMapper.writeValueAsString(payload),
                            currentTraceparent()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event: " + eventType, e);
        }
    }

    private OutboxEvent toOutboxEvent(Object event, String traceparent) {
        try {
            return OutboxEvent.create(
                    event.getClass().getSimpleName(),
                    objectMapper.writeValueAsString(event),
                    traceparent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize event: " + event.getClass().getSimpleName(), e);
        }
    }

    // Reads the W3C traceparent off whatever span is currently active (the HTTP request span, or —
    // when this write happens from inside an OutboxEventHandler reacting to a prior event — the
    // span OutboxConsumer started for that event), so the trace context survives this async
    // boundary too (observability.md, "Metrics and tracing"). Null when there's no active span.
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(
                span.context(),
                carrier,
                (c, k, v) -> {
                    if (c != null) {
                        c.put(k, v);
                    }
                });
        return carrier.get("traceparent");
    }
}
