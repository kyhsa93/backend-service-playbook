import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

import { computeSpendingForecastMonth } from './spending-forecast-month'

// The same principle as SpendingAnalysisScheduler — Infrastructure layer, calling only
// TaskQueue.enqueue.
@Injectable()
export class SpendingForecastScheduler {
  private readonly logger = new Logger(SpendingForecastScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  // The 1st of every month at 03:00 UTC — an hour after the spending-analysis job (02:00), so
  // this month's history (last month's freshly-written analysis row) is guaranteed to exist
  // before training reads it.
  @Cron('0 3 1 * *')
  public async enqueueMonthlySpendingForecast(): Promise<void> {
    const forecastMonth = computeSpendingForecastMonth(new Date())
    const dedupId = `account.forecast-spending-${forecastMonth}`

    try {
      await this.taskQueue.enqueue(
        'account.forecast-spending',
        { forecastMonth },
        { groupId: 'account.spending-forecast', deduplicationId: dedupId }
      )
      this.logger.log({ message: 'Monthly spending forecast Task enqueued', forecast_month: forecastMonth, dedup_id: dedupId })
    } catch (error) {
      // @nestjs/schedule silently swallows exceptions from Cron handlers, so log explicitly.
      this.logger.error({ message: 'Failed to enqueue monthly spending forecast Task', dedup_id: dedupId, error })
    }
  }
}
