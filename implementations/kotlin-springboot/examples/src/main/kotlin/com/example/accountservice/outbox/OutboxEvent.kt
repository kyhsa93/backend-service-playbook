package com.example.accountservice.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * An Outbox row for saving a Domain Event within the same transaction as the Aggregate (see
 * docs/architecture/domain-events.md).
 *
 * The Repository commits this Entity in the same transaction as the Aggregate save. Afterward,
 * [OutboxPoller] runs independently on its own schedule, publishing unprocessed rows to SQS
 * (`processed=true`), and [OutboxConsumer] waits to receive from SQS and delivers to the handler via
 * [EventHandlerRegistry].
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var eventId: String = ""
        private set

    @Column(nullable = false)
    var eventType: String = ""
        private set

    @Column(nullable = false, columnDefinition = "TEXT")
    var payload: String = ""
        private set

    @Column(nullable = false)
    var processed: Boolean = false
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    // The W3C traceparent of the span active at write time (observability.md), so OutboxPoller/
    // OutboxConsumer can carry the trace context across the async queue boundary the same way
    // eventType/eventId already do (as an SQS message attribute). Null when no span is active
    // (e.g. a background write with no request/consumer scope).
    @Column
    var traceparent: String? = null
        private set

    companion object {
        fun from(
            event: Any,
            objectMapper: ObjectMapper,
            traceparent: String?,
        ): OutboxEvent =
            OutboxEvent().apply {
                this.eventId = UUID.randomUUID().toString().replace("-", "")
                // An Integration Event uses a versioned public contract name (eventName, e.g.
                // `account.suspended.v1`) as its eventType. A Domain Event has no eventName, so it uses
                // its class name as-is.
                this.eventType = (event as? IntegrationEventContract)?.eventName ?: (event::class.simpleName ?: "Unknown")
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
                this.traceparent = traceparent
            }
    }

    fun markProcessed() {
        processed = true
    }
}
