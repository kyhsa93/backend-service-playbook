package com.example.accountservice.outbox;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes a delivered domain/integration event to every {@link OutboxEventHandler} registered for
 * its {@code eventType} — Spring auto-collects all {@code OutboxEventHandler} beans across the
 * classpath via constructor injection (the same {@code List<OutboxEventHandler>} mechanism {@link
 * OutboxConsumer} used to own directly), grouped here by {@code eventType()} so more than one
 * handler can subscribe to the same event (the "1:N" contract this package documents — see
 * docs/architecture/domain-events.md).
 *
 * <p>Each handler is independent: one subscriber's failure must not prevent a sibling subscriber on
 * the same eventType from running. Every handler still gets a chance to run on every delivery, but
 * {@link #dispatch} rethrows if any of them failed, so {@link OutboxConsumer} leaves the SQS
 * message unacked and it gets redelivered — each handler must already be idempotent for that
 * redelivery (see "Event Handler Idempotency" in domain-events.md).
 */
@Component
public class OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDispatcher.class);

    private final Map<String, List<OutboxEventHandler>> handlersByEventType;

    public OutboxEventDispatcher(List<OutboxEventHandler> handlers) {
        this.handlersByEventType =
                handlers.stream().collect(Collectors.groupingBy(OutboxEventHandler::eventType));
    }

    /**
     * Runs every handler registered for {@code eventType} against {@code payload}. A handler that
     * throws is logged and does not stop the remaining handlers from running, but once all of them
     * have had a chance to run, the first failure encountered is rethrown so the caller can leave
     * the delivery unacked.
     *
     * @throws IllegalStateException if no handler is registered for {@code eventType}
     */
    public void dispatch(String eventType, String payload) throws Exception {
        List<OutboxEventHandler> handlers = handlersByEventType.get(eventType);
        if (handlers == null || handlers.isEmpty()) {
            throw new IllegalStateException("No handler registered for: " + eventType);
        }

        Exception firstFailure = null;
        for (OutboxEventHandler handler : handlers) {
            try {
                handler.handle(payload);
            } catch (Exception e) {
                log.error("A handler failed for eventType={}", eventType, e);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
