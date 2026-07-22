package com.example.accountservice.outbox

/**
 * The marker interface implemented by Integration Events (public contracts) exposed to external BCs.
 *
 * A Domain Event uses its class name as-is for the Outbox row's eventType, but an Integration Event uses
 * a versioned public contract name ([eventName], e.g. `account.suspended.v1`) as its eventType —
 * [OutboxEvent.from] distinguishes the two by whether this interface is implemented. The eventName
 * literal becomes the routing key ([EventHandlerRegistry], SQS `MessageAttributes.eventType`).
 */
interface IntegrationEventContract {
    val eventName: String
}
