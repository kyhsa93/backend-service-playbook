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
 * Repository가 Aggregate 저장과 함께 이 Entity를 같은 트랜잭션에 커밋한다. 이후 [OutboxPoller]가
 * 독립적으로 주기 실행되며 미처리 행을 SQS로 발행하고(`processed=true`), [OutboxConsumer]가 SQS를
 * 수신 대기하다가 [EventHandlerRegistry]를 통해 핸들러에 전달한다.
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
        fun from(
            event: Any,
            objectMapper: ObjectMapper,
        ): OutboxEvent =
            OutboxEvent().apply {
                this.eventId = UUID.randomUUID().toString().replace("-", "")
                // Integration Event는 버전이 명시된 공개 계약명(eventName, 예: `account.suspended.v1`)을
                // eventType으로 쓴다. Domain Event는 eventName이 없으므로 클래스명을 그대로 쓴다.
                this.eventType = (event as? IntegrationEventContract)?.eventName ?: (event::class.simpleName ?: "Unknown")
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() {
        processed = true
    }
}
