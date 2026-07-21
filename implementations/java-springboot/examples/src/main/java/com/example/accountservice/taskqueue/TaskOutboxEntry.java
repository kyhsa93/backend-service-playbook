package com.example.accountservice.taskqueue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Scheduler가 적재한 Task를 Task Queue(SQS)로 발행하기 전 보관하는 Outbox 레코드 — {@code outbox/OutboxEvent}와 동일한
 * 이유·구조다(domain-events.md의 Outbox 패턴을 Task에도 그대로 적용, scheduling.md "Task Outbox 패턴" 참고).
 * Scheduler(Cron)는 자연스러운 DB 트랜잭션 문맥이 없으므로, 이 테이블에 대한 단일 row insert 자체가 원자적 적재 단위가 된다.
 *
 * <p>{@code groupId}/{@code deduplicationId}는 FIFO Task Queue의 MessageGroupId/
 * MessageDeduplicationId로 그대로 전달된다({@link TaskOutboxPoller} 참고) — 여러 인스턴스가 같은 시각에 Cron을 실행해도 날짜/월
 * 기반 deduplicationId 덕분에 큐에는 1건만 들어간다(scheduling.md "Cron 다중 인스턴스 안전성"). Domain Event 큐(표준 큐)와 달리
 * Task Queue를 FIFO로 두는 이유이기도 하다.
 */
@Entity
@Table(name = "task_outbox")
public class TaskOutboxEntry {

    @Id
    @Column(length = 32, nullable = false, updatable = false)
    private String taskId;

    @Column(nullable = false, updatable = false)
    private String taskType;

    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(nullable = false, updatable = false)
    private String groupId;

    @Column(nullable = false, updatable = false)
    private String deduplicationId;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TaskOutboxEntry() {}

    public static TaskOutboxEntry create(
            String taskType, String payload, String groupId, String deduplicationId) {
        TaskOutboxEntry entry = new TaskOutboxEntry();
        entry.taskId = UUID.randomUUID().toString().replace("-", "");
        entry.taskType = taskType;
        entry.payload = payload;
        entry.groupId = groupId;
        entry.deduplicationId = deduplicationId;
        entry.processed = false;
        entry.createdAt = LocalDateTime.now();
        return entry;
    }

    public void markProcessed() {
        this.processed = true;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getDeduplicationId() {
        return deduplicationId;
    }

    public boolean isProcessed() {
        return processed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
