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
 * Domain Event를 Aggregate와 같은 트랜잭션 안에서 저장하기 위한 Outbox 행 (docs/architecture/domain-events.md 참조).
 *
 * Repository가 Aggregate 저장과 함께 이 Entity를 같은 트랜잭션에 커밋하고, [OutboxRelay]가 트랜잭션
 * 커밋 직후 동기적으로 미처리 행을 모두 꺼내 핸들러에 전달한다.
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

    companion object {
        fun from(event: Any, objectMapper: ObjectMapper): OutboxEvent =
            OutboxEvent().apply {
                this.eventId = UUID.randomUUID().toString().replace("-", "")
                this.eventType = event::class.simpleName ?: "Unknown"
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() {
        processed = true
    }
}
