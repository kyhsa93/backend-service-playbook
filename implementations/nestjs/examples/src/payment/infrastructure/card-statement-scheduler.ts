import { Injectable, Logger } from '@nestjs/common'
import { Cron } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

import { computePreviousStatementMonth } from './previous-statement-month'

// account/infrastructure/account-interest-scheduler.ts와 같은 원칙 — Infrastructure
// 레이어, TaskQueue.enqueue만 호출한다.
@Injectable()
export class CardStatementScheduler {
  private readonly logger = new Logger(CardStatementScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  // 매월 1일 01:00(UTC) — 직전 달이 완전히 끝난 뒤 실행한다.
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
      // @nestjs/schedule은 Cron 핸들러의 예외를 조용히 삼키므로 명시적으로 로깅한다.
      this.logger.error({ message: '월간 카드 사용내역 Task 적재 실패', dedup_id: dedupId, error })
    }
  }
}
