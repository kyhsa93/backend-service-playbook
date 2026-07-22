import { Injectable, Logger } from '@nestjs/common'
import { Cron, CronExpression } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

// The Scheduler lives only in the Infrastructure layer and never executes business logic
// directly — it only calls TaskQueue.enqueue (see docs/architecture/scheduling.md, the
// Scheduler section).
@Injectable()
export class AccountInterestScheduler {
  private readonly logger = new Logger(AccountInterestScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  @Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
  public async enqueueDailyInterest(): Promise<void> {
    // A date-unit deduplicationId — even if multiple instances run the Cron at the same
    // midnight, only one enters the queue within the FIFO queue's dedup window
    // (see docs/architecture/scheduling.md, the Cron multi-instance safety section).
    const now = new Date()
    const today = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()))
    const dateStamp = today.toISOString().slice(0, 10)
    const dedupId = `account.apply-daily-interest-${dateStamp}`

    try {
      await this.taskQueue.enqueue(
        'account.apply-daily-interest',
        { today: today.toISOString() },
        { groupId: 'account.interest', deduplicationId: dedupId }
      )
      this.logger.log({ message: '일 이자 지급 Task 적재', dedup_id: dedupId })
    } catch (error) {
      // @nestjs/schedule silently swallows exceptions from Cron handlers, so log explicitly.
      this.logger.error({ message: '일 이자 지급 Task 적재 실패', dedup_id: dedupId, error })
    }
  }
}
