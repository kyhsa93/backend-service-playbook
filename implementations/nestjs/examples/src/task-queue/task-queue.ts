// Task Queue의 적재(enqueue) 계약(abstract class). Scheduler(Cron)와 Application
// Service 둘 다 이 인터페이스만 의존한다 — 실제로는 task_outbox 테이블에 쓰는
// TaskQueueOutbox가 유일한 구현체다(docs/architecture/scheduling.md#taskqueue--outbox-기반-구현).
export type EnqueueOptions = {
  readonly groupId: string
  readonly deduplicationId: string
  readonly delaySeconds?: number
}

export abstract class TaskQueue {
  abstract enqueue(taskType: string, payload: object, options: EnqueueOptions): Promise<void>
}
