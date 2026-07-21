package com.example.accountservice.taskqueue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * Task Outbox 행 — Domain Event의 `outbox/OutboxEvent.kt`와 동일한 목적이다
 * (docs/architecture/scheduling.md "Task Outbox 패턴"). 차이는 발행 주체다: OutboxEvent는
 * Aggregate가 트랜잭션 안에서 쌓은 이벤트를 Repository가 저장하지만, TaskOutbox는 Scheduler(또는
 * 향후 Command Service)가 [TaskQueue.enqueue]를 통해 직접 한 행을 적재한다 — Cron tick에는 자연스러운
 * DB 트랜잭션이 없으므로, "단일 row insert 자체가 원자적 단위"라는 점이 이 테이블의 존재 이유다.
 *
 * [deduplicationId]는 `<taskType>-<date>`/`<taskType>-<yearMonth>` 형태의 결정론적 값이며 DB
 * 레벨 UNIQUE 제약을 가진다 — 여러 인스턴스가 같은 Cron을 동시에 실행해도 두 번째 insert는
 * 유니크 제약 위반으로 실패한다([com.example.accountservice.taskqueue.TaskOutboxWriter]가 이를
 * 잡아 "이미 적재됨"으로 처리한다). [groupId]/[deduplicationId]는 SQS FIFO 큐 발행 시 그대로
 * MessageGroupId/MessageDeduplicationId로 쓰인다.
 */
@Entity
@Table(name = "task_outbox")
class TaskOutbox protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var taskId: String = ""
        private set

    @Column(nullable = false)
    var taskType: String = ""
        private set

    @Column(nullable = false, columnDefinition = "TEXT")
    var payload: String = ""
        private set

    @Column(nullable = false)
    var groupId: String = ""
        private set

    @Column(nullable = false, unique = true)
    var deduplicationId: String = ""
        private set

    @Column(nullable = false)
    var processed: Boolean = false
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun create(
            taskType: String,
            payload: String,
            groupId: String,
            deduplicationId: String,
        ): TaskOutbox =
            TaskOutbox().apply {
                this.taskId = UUID.randomUUID().toString().replace("-", "")
                this.taskType = taskType
                this.payload = payload
                this.groupId = groupId
                this.deduplicationId = deduplicationId
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() {
        processed = true
    }
}
