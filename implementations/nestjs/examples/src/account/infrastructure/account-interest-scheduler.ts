import { Injectable, Logger } from '@nestjs/common'
import { Cron, CronExpression } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

// Scheduler는 Infrastructure 레이어에만 위치하고, 비즈니스 로직을 직접 실행하지 않는다 —
// TaskQueue.enqueue만 호출한다(docs/architecture/scheduling.md#scheduler-역할-분리).
@Injectable()
export class AccountInterestScheduler {
  private readonly logger = new Logger(AccountInterestScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  @Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
  public async enqueueDailyInterest(): Promise<void> {
    // 날짜 단위 deduplicationId — 여러 인스턴스가 같은 자정에 동시에 Cron을 실행해도
    // FIFO 큐의 중복 제거 윈도우 안에서는 1건만 큐에 들어간다
    // (docs/architecture/scheduling.md#cron-다중-인스턴스-안전성).
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
      // @nestjs/schedule은 Cron 핸들러의 예외를 조용히 삼키므로 명시적으로 로깅한다.
      this.logger.error({ message: '일 이자 지급 Task 적재 실패', dedup_id: dedupId, error })
    }
  }
}
