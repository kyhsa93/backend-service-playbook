package com.example.accountservice.taskqueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Scheduler가 Task를 task_outbox 테이블에 적재한다 — {@code outbox/OutboxWriter}와 동일한 역할을 Task에 대해 수행한다.
 * Scheduler(Cron)는 자연스러운 DB 트랜잭션 문맥이 없으므로, 이 단일 insert 자체가 원자적 적재 단위다(scheduling.md "Task Outbox
 * 패턴" — dual-write 문제 차단).
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Task 페이로드 직렬화 실패: " + taskType, e);
        }
    }
}
