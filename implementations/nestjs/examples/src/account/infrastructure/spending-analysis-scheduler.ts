import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

import { computePreviousSpendingAnalysisPeriod } from './previous-spending-analysis-period'

// The same principle as account/infrastructure/account-interest-scheduler.ts — Infrastructure
// layer, calling only TaskQueue.enqueue.
@Injectable()
export class SpendingAnalysisScheduler {
  private readonly logger = new Logger(SpendingAnalysisScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  // The 1st of every month at 02:00 UTC — an hour after the card-statement job, to avoid both
  // batch jobs contending for the database at the exact same moment.
  @Cron('0 2 1 * *')
  public async enqueueMonthlySpendingAnalysis(): Promise<void> {
    const { analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd } =
      computePreviousSpendingAnalysisPeriod(new Date())
    const dedupId = `account.analyze-monthly-spending-${analysisMonth}`

    try {
      await this.taskQueue.enqueue(
        'account.analyze-monthly-spending',
        {
          analysisMonth,
          monthStart: monthStart.toISOString(),
          monthEnd: monthEnd.toISOString(),
          previousMonthStart: previousMonthStart.toISOString(),
          previousMonthEnd: previousMonthEnd.toISOString()
        },
        { groupId: 'account.spending-analysis', deduplicationId: dedupId }
      )
      this.logger.log({ message: 'Monthly spending analysis Task enqueued', analysis_month: analysisMonth, dedup_id: dedupId })
    } catch (error) {
      // @nestjs/schedule silently swallows exceptions from Cron handlers, so log explicitly.
      this.logger.error({ message: 'Failed to enqueue monthly spending analysis Task', dedup_id: dedupId, error })
    }
  }
}
