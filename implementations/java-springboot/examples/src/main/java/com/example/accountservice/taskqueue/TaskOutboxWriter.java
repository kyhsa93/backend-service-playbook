package com.example.accountservice.taskqueue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * A Scheduler enqueues a Task into the task_outbox table — performing the same role for Tasks that
 * {@code outbox/OutboxWriter} performs for events. A Scheduler (Cron) has no natural DB transaction
 * context, so this single insert is itself the atomic unit of enqueueing (see the "Task Outbox
 * pattern" in scheduling.md — blocking the dual-write problem).
 */
@Component
@RequiredArgsConstructor
public class TaskOutboxWriter {

    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void enqueue(String taskType, Object payload, String groupId, String deduplicationId) {
        try {
            taskOutboxJpaRepository.save(
                    TaskOutboxEntry.create(
                            taskType,
                            objectMapper.writeValueAsString(payload),
                            groupId,
                            deduplicationId));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize task payload: " + taskType, e);
        }
    }
}
