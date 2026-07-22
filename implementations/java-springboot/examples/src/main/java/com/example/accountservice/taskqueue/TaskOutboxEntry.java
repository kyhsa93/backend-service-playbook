package com.example.accountservice.taskqueue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An Outbox record that holds a Task enqueued by a Scheduler before it's published to the Task
 * Queue (SQS) — the same rationale and structure as {@code outbox/OutboxEvent} (applying the Outbox
 * pattern from domain-events.md to Tasks as well; see the "Task Outbox pattern" in scheduling.md).
 * A Scheduler (Cron) has no natural DB transaction context, so a single row insert into this table
 * is itself the atomic unit of enqueueing.
 *
 * <p>{@code groupId}/{@code deduplicationId} are passed straight through as the FIFO Task Queue's
 * MessageGroupId/MessageDeduplicationId (see {@link TaskOutboxPoller}) — even if multiple instances
 * run the same Cron at the same moment, a date/month-based deduplicationId ensures only one entry
 * lands in the queue (see "Cron safety with multiple instances" in scheduling.md). This is also why
 * the Task Queue is FIFO, unlike the Domain Event queue (a standard queue).
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
