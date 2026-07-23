package com.example.accountservice.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.springframework.stereotype.Component

/**
 * Converts the list of Domain Events collected by an Aggregate into Outbox rows and saves them.
 *
 * [com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl.save] calls this
 * class within the same method call (= the same transaction) as the Aggregate save — the Aggregate
 * state and the events are committed atomically together or rolled back together (avoiding the
 * dual-write problem).
 */
@Component
class OutboxWriter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val tracer: Tracer,
    private val propagator: Propagator,
) {
    fun saveAll(events: List<Any>) {
        if (events.isEmpty()) return
        val traceparent = currentTraceparent()
        outboxEventJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper, traceparent) })
    }

    // Reads the W3C traceparent off whatever span is currently active (the HTTP request span, or —
    // when this write happens from inside an EventHandler reacting to a prior event — the span
    // OutboxConsumer started for that event), so the trace context survives this async boundary too
    // (observability.md, "Metrics and tracing"). Null when there's no active span.
    private fun currentTraceparent(): String? {
        val span = tracer.currentSpan() ?: return null
        val carrier = mutableMapOf<String, String>()
        propagator.inject(span.context(), carrier) { c, k, v -> c?.set(k, v) }
        return carrier["traceparent"]
    }
}
