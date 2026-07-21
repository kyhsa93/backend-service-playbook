import { Inject, Injectable, Logger } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'
import { SendMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { TransactionManager } from '@/database/transaction-manager'
import { getTaskQueueUrl } from '@/config/aws.config'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

import { TaskOutboxEntity } from './task-outbox.entity'

// task_outbox 테이블 → SQS 발행만 담당한다(outbox/outbox-poller.ts와 같은 모양). 어떤
// Task Controller도 직접 호출하지 않는다 — 그건 TaskQueueConsumer의 몫이다.
//
// SQS 클라이언트는 outbox 모듈이 export하는 SQS_CLIENT를 그대로 재사용한다 — Task Queue와
// Domain Event 큐는 개념이 달라 큐는 분리하지만, "같은 SDK/인프라를 재사용한다"는
// scheduling.md의 원칙에 따라 커넥션은 공유한다.
@Injectable()
export class TaskOutboxRelay {
  private readonly logger = new Logger(TaskOutboxRelay.name)
  private isPolling = false

  constructor(
    private readonly transactionManager: TransactionManager,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  @Interval(3000)
  public async relay(): Promise<void> {
    if (this.isPolling) return
    this.isPolling = true
    try {
      await this.drainOnce()
    } catch (error) {
      this.logger.error({ message: 'Task Outbox 폴링 실패', error })
    } finally {
      this.isPolling = false
    }
  }

  private async drainOnce(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.find(TaskOutboxEntity, {
      where: { processed: false },
      order: { createdAt: 'ASC' },
      take: 100
    })
    if (rows.length === 0) return

    const queueUrl = getTaskQueueUrl()
    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: queueUrl,
          MessageBody: row.payload,
          MessageAttributes: {
            taskType: { DataType: 'String', StringValue: row.taskType }
          },
          MessageGroupId: row.groupId,
          MessageDeduplicationId: row.deduplicationId,
          ...(row.delaySeconds !== null ? { DelaySeconds: row.delaySeconds } : {})
        }))
        await manager.update(TaskOutboxEntity, { taskId: row.taskId }, { processed: true })
      } catch (error) {
        // 발행 실패 행은 processed=false로 남겨 다음 tick에서 재시도한다.
        this.logger.error({ message: 'Task SQS 발행 실패', task_type: row.taskType, task_id: row.taskId, error })
      }
    }
  }
}
