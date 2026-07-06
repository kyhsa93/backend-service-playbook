package com.example.accountservice.outbox

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventJpaRepository : JpaRepository<OutboxEvent, Long> {
    fun findByProcessedFalseOrderByCreatedAtAsc(): List<OutboxEvent>
}
