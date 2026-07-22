import { Inject, Injectable, Logger } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'
import { SendMessageCommand, SQSClient } from '@aws-sdk/client-sqs'

import { getDomainEventQueueUrl } from '@/config/aws.config'
import { TransactionManager } from '@/database/transaction-manager'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { SQS_CLIENT } from '@/outbox/sqs-client-provider'

// Handles only publishing Outbox table → SQS ("carrying events accumulated in the DB to the
// queue"). It never calls any EventHandler directly — that's OutboxConsumer's job.
//
// The Command Service/Handler never references this class at all. The fact that this class
// runs independently on its own schedule is exactly what removes "synchronous draining in the
// same process right after saving" — even after a Command commits its save and returns a
// response, there's no way to know when this event goes out to the queue until the next tick (up to 1 second later).
//
// processed=true now means "delivery to SQS is done," not "the handler finished processing" —
// from here on, retry/at-least-once guarantees are the job of SQS's visibility timeout + DLQ,
// not the outbox table (see docs/architecture/domain-events.md).
@Injectable()
export class OutboxPoller {
  private readonly logger = new Logger(OutboxPoller.name)
  // Don't run overlapping if the previous tick's drain hasn't finished yet — in case there are
  // enough rows to process that it takes longer than the polling interval (1 second).
  private isPolling = false

  constructor(
    private readonly transactionManager: TransactionManager,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  @Interval(1000)
  public async poll(): Promise<void> {
    if (this.isPolling) return
    this.isPolling = true
    try {
      await this.drainOnce()
    } catch (error) {
      this.logger.error({ message: 'Outbox 폴링 실패', error })
    } finally {
      this.isPolling = false
    }
  }

  private async drainOnce(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.find(OutboxEntity, {
      where: { processed: false },
      order: { createdAt: 'ASC' },
      take: 100
    })
    if (rows.length === 0) return

    const queueUrl = getDomainEventQueueUrl()
    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: queueUrl,
          MessageBody: row.payload,
          MessageAttributes: {
            eventType: { DataType: 'String', StringValue: row.eventType }
          }
        }))
        await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
      } catch (error) {
        // Leave a publish-failed row as processed=false so it retries on the next tick.
        this.logger.error({ message: 'SQS 발행 실패', event_type: row.eventType, event_id: row.eventId, error })
      }
    }
  }
}
