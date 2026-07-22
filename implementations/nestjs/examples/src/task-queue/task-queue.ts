// The Task Queue's enqueue contract (abstract class). Both the Scheduler (Cron) and the
// Application Service depend only on this interface — in practice, TaskQueueOutbox, which
// writes to the task_outbox table, is the only implementation (see
// docs/architecture/scheduling.md, the TaskQueue Outbox-based Implementation section).
export type EnqueueOptions = {
  readonly groupId: string
  readonly deduplicationId: string
  readonly delaySeconds?: number
}

export abstract class TaskQueue {
  abstract enqueue(taskType: string, payload: object, options: EnqueueOptions): Promise<void>
}
