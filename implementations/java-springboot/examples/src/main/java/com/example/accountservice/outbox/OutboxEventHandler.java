package com.example.accountservice.outbox;

/**
 * A handler that processes one domain event accumulated in the Outbox table. {@link #eventType()}
 * must match the simple name of the domain event record that published the event (e.g. {@code
 * AccountCreatedEvent}) for {@link OutboxConsumer} to route it to the correct handler.
 */
public interface OutboxEventHandler {

    String eventType();

    void handle(String payload) throws Exception;
}
