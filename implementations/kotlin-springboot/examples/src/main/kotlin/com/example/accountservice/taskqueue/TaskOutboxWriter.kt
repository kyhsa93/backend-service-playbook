package com.example.accountservice.taskqueue

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * [TaskQueue]의 구현체 — `task_outbox`에 한 행을 적재한다. Scheduler(Cron)에는 자연스러운 DB
 * 트랜잭션이 없으므로, 이 메서드 하나(= Spring Data JPA `save()` 한 번)가 곧 원자적 단위다
 * (scheduling.md "단일 row insert이므로 자연스럽게 atomic").
 *
 * [deduplicationId] UNIQUE 제약을 이용해 다중 인스턴스 안전성을 구현한다 — 여러 인스턴스가 같은
 * 날짜/월에 동시에 enqueue를 시도하면 두 번째 insert부터는
 * [DataIntegrityViolationException]이 발생하는데, 이는 버그가 아니라 "이미 적재됨"이라는 정상
 * 신호이므로 별도로 잡아 info 로그만 남기고 조용히 넘어간다(그 외의 예외는 그대로 던져 Scheduler의
 * `runCatching { }.onFailure { }`가 로깅하게 한다).
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
                .log("이미 적재된 Task — 중복 enqueue 스킵(다중 인스턴스 안전성)")
        }
    }
}
