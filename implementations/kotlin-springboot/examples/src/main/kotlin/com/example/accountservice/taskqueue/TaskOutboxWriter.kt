package com.example.accountservice.taskqueue

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * The implementation of [TaskQueue] — inserts a single row into `task_outbox`. The Scheduler (Cron) has
 * no natural DB transaction of its own, so this one method call (= a single Spring Data JPA `save()`)
 * is itself the atomic unit (scheduling.md "naturally atomic because it's a single row insert").
 *
 * Multi-instance safety is implemented using the [deduplicationId] UNIQUE constraint — if multiple
 * instances try to enqueue for the same date/month at the same time, the second insert onward raises
 * [DataIntegrityViolationException]. This isn't a bug but a normal "already enqueued" signal, so it's
 * caught separately, logged at info level, and quietly skipped (any other exception is thrown as-is so
 * the Scheduler's `runCatching { }.onFailure { }` logs it).
 */
@Component
class TaskOutboxWriter(
    private val taskOutboxJpaRepository: TaskOutboxJpaRepository,
) : TaskQueue {
    private val logger = LoggerFactory.getLogger(TaskOutboxWriter::class.java)

    override fun enqueue(
        taskType: String,
        payload: String,
        groupId: String,
        deduplicationId: String,
    ) {
        try {
            taskOutboxJpaRepository.save(TaskOutbox.create(taskType, payload, groupId, deduplicationId))
        } catch (e: DataIntegrityViolationException) {
            logger
                .atInfo()
                .addKeyValue("task_type", taskType)
                .addKeyValue("deduplication_id", deduplicationId)
                .setCause(e)
                .log("Task already enqueued — skipping duplicate enqueue (multi-instance safety)")
        }
    }
}
