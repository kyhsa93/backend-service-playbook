package com.example.accountservice.outbox

import com.fasterxml.jackson.databind.ObjectMapper
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
) {
    fun saveAll(events: List<Any>) {
        if (events.isEmpty()) return
        outboxEventJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper) })
    }
}
