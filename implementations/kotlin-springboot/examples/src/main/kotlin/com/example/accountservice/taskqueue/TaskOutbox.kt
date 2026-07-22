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
 * A Task Outbox row — it serves the same purpose as the Domain Event's `outbox/OutboxEvent.kt`
 * (docs/architecture/scheduling.md "Task Outbox pattern"). The difference is who publishes it: for
 * OutboxEvent, the Repository saves events the Aggregate accumulated within a transaction, but for
 * TaskOutbox, the Scheduler (or, in the future, a Command Service) inserts a single row directly via
 * [TaskQueue.enqueue] — a Cron tick has no natural DB transaction of its own, so this table exists
 * precisely because "the single row insert itself is the atomic unit."
 *
 * [deduplicationId] is a deterministic value shaped like `<taskType>-<date>`/`<taskType>-<yearMonth>`
 * and carries a DB-level UNIQUE constraint — even if multiple instances run the same Cron at the same
 * time, the second insert fails with a unique-constraint violation
 * ([com.example.accountservice.taskqueue.TaskOutboxWriter] catches this and treats it as "already
 * enqueued"). [groupId]/[deduplicationId] are used as-is for MessageGroupId/MessageDeduplicationId when
 * publishing to the SQS FIFO queue.
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
