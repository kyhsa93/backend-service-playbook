package com.example.accountservice.taskqueue;

/**
 * A contract for processing one Task Queue message. {@code taskType()} must match the taskType the
 * Scheduler passed to {@link TaskOutboxWriter#enqueue} for {@link TaskConsumer} to route it to the
 * correct handler. Implementations are the Task Controller located under each domain's {@code
 * interfaces/task/} — just as an HTTP Controller delegates to an Application Service, this handler
 * only calls a Command Service and holds no business logic itself (see "Task Controller — the
 * Interface layer" in scheduling.md). Exceptions must be thrown as-is, not swallowed, so that
 * {@link TaskConsumer} can leave the message undeleted for retry (at-least-once) / DLQ.
 */
public interface TaskHandler {

    String taskType();

    void handle(String payload) throws Exception;
}
