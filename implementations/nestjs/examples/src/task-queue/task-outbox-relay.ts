import { Inject, Injectable, Logger } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'
import { SendMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { TransactionManager } from '@/database/transaction-manager'
import { getTaskQueueUrl } from '@/config/aws.config'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

import { TaskOutboxEntity } from './task-outbox.entity'

// Handles only publishing task_outbox table → SQS (shaped like outbox/outbox-poller.ts). It
// never calls any Task Controller directly — that's TaskQueueConsumer's job.
//
// The SQS client reuses SQS_CLIENT exported by the outbox module as-is — the Task Queue and
// the Domain Event queue are different concepts so the queues themselves are kept separate,
// but per scheduling.md's principle of "reusing the same SDK/infrastructure," the connection is shared.
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
        // Leave a publish-failed row as processed=false so it retries on the next tick.
        this.logger.error({ message: 'Task SQS 발행 실패', task_type: row.taskType, task_id: row.taskId, error })
      }
    }
  }
}
