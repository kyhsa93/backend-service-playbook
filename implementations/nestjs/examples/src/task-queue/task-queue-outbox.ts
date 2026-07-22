import { Injectable } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'

import { EnqueueOptions, TaskQueue } from './task-queue'
import { TaskOutboxEntity } from './task-outbox.entity'

// TaskQueue's Outbox-based implementation. Since it participates in TransactionManager's
// current transaction context, calling it inside a Command transaction binds the DB change
// and the Task enqueue atomically (blocking dual-write). Even called from somewhere with no
// transaction context, like the Scheduler (Cron), it's naturally atomic as a single row insert
// — since the path is unified, the caller doesn't need to worry about whether a transaction
// exists (see docs/architecture/scheduling.md, the TaskQueue Outbox-based Implementation section).
@Injectable()
export class TaskQueueOutbox extends TaskQueue {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async enqueue(taskType: string, payload: object, options: EnqueueOptions): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.insert(TaskOutboxEntity, {
      taskId: generateId(),
      taskType,
      payload: JSON.stringify(payload),
      groupId: options.groupId,
      deduplicationId: options.deduplicationId,
      delaySeconds: options.delaySeconds ?? null,
      processed: false
    })
  }
}
