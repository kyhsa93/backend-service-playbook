import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

import { computePreviousStatementMonth } from './previous-statement-month'

// The same principle as account/infrastructure/account-interest-scheduler.ts — Infrastructure
// layer, calling only TaskQueue.enqueue.
@Injectable()
export class CardStatementScheduler {
  private readonly logger = new Logger(CardStatementScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  // The 1st of every month at 01:00 (UTC) — runs after the previous month has fully ended.
  @Cron('0 1 1 * *')
  public async enqueueMonthlyCardStatements(): Promise<void> {
    const { statementMonth, monthStart, monthEnd } = computePreviousStatementMonth(new Date())
    const dedupId = `payment.send-card-statements-${statementMonth}`

    try {
      await this.taskQueue.enqueue(
        'payment.send-card-statements',
        { statementMonth, monthStart: monthStart.toISOString(), monthEnd: monthEnd.toISOString() },
        { groupId: 'payment.card-statement', deduplicationId: dedupId }
      )
      this.logger.log({ message: '월간 카드 사용내역 Task 적재', statement_month: statementMonth, dedup_id: dedupId })
    } catch (error) {
      // @nestjs/schedule silently swallows exceptions from Cron handlers, so log explicitly.
      this.logger.error({ message: '월간 카드 사용내역 Task 적재 실패', dedup_id: dedupId, error })
    }
  }
}
