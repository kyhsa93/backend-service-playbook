package com.example.accountservice.taskqueue

/**
 * The port for enqueueing a Task (the same name and role as the `TaskQueue` example in
 * docs/architecture/scheduling.md). The Scheduler (Infrastructure layer, a `@Scheduled` method) depends
 * only on this interface — in reality, nothing is sent straight to SQS; [TaskOutboxWriter] merely
 * inserts a single row into the `task_outbox` table (the "enqueue goes through the Outbox" principle,
 * to avoid dual-write).
 *
 * Unlike a Domain Event (the `List<Any>` that
 * [com.example.accountservice.outbox.OutboxWriter] receives), a Task is "a command: perform X," so it's
 * represented not as an event object but as a taskType (a string identifier) + payload (a JSON string) —
 * the Task Queue vs. Domain Event distinction that root scheduling.md/domain-events.md prescribes shows
 * up directly in the type signature too.
 */
interface TaskQueue {
    /**
     * @param taskType e.g. `"account.pay-interest"`, `"card.send-statement"` — [TaskHandlerRegistry]
     *   uses this value to find the handler.
     * @param payload An already-serialized JSON string — don't put large data in it (the SQS message
     *   256KB limit, scheduling.md "payload size limit").
     * @param groupId The FIFO MessageGroupId — this repository handles only one batch Task per
     *   taskType, so it uses the taskType itself as the groupId (scheduling.md's "global Cron batch"
     *   row).
     * @param deduplicationId The FIFO MessageDeduplicationId and also the value of the
     *   `task_outbox.deduplication_id` UNIQUE constraint — the caller constructs it in the form
     *   `<taskType>-<date>`/`<taskType>-<yearMonth>`.
     */
    fun enqueue(
        taskType: String,
        payload: String,
        groupId: String,
        deduplicationId: String,
    )
}
