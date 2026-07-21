import { Injectable } from '@nestjs/common'

import { generateId } from '@/common/generate-id'
import { TransactionManager } from '@/database/transaction-manager'

import { EnqueueOptions, TaskQueue } from './task-queue'
import { TaskOutboxEntity } from './task-outbox.entity'

// TaskQueue의 Outbox 기반 구현체. TransactionManager의 현재 트랜잭션 문맥에 참여하므로,
// Command 트랜잭션 안에서 호출되면 DB 변경과 Task 적재가 원자적으로 묶인다(dual-write
// 차단). Scheduler(Cron)처럼 트랜잭션 문맥이 없는 곳에서 호출돼도 단일 row insert이므로
// 자연스럽게 atomic하다 — 경로가 통일되어 있어 호출자는 트랜잭션 유무를 신경 쓰지 않는다
// (docs/architecture/scheduling.md#taskqueue--outbox-기반-구현).
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
