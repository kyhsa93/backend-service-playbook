import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { SpendingAnalysis, SpendingTrend } from '@/account/domain/spending-analysis'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { SpendingAnalysisEntity } from '@/account/infrastructure/entity/spending-analysis.entity'

@Injectable()
export class SpendingAnalysisRepositoryImpl extends SpendingAnalysisRepository {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async saveAnalysis(analysis: SpendingAnalysis): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(SpendingAnalysisEntity, {
      analysisId: analysis.analysisId,
      accountId: analysis.accountId,
      analysisMonth: analysis.analysisMonth,
      totalAmount: analysis.totalAmount,
      transactionCount: analysis.transactionCount,
      averageAmount: analysis.averageAmount,
      changeFromPreviousMonth: analysis.changeFromPreviousMonth,
      trend: analysis.trend,
      createdAt: analysis.createdAt
    })
  }

  public async hasAnalysis(accountId: string, analysisMonth: string): Promise<boolean> {
    const manager = this.transactionManager.getManager()
    const count = await manager.count(SpendingAnalysisEntity, { where: { accountId, analysisMonth } })
    return count > 0
  }

  public async findRecentAnalyses(accountId: string, beforeMonth: string, limit: number): Promise<SpendingAnalysis[]> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.createQueryBuilder(SpendingAnalysisEntity, 'analysis')
      .where('analysis.accountId = :accountId', { accountId })
      .andWhere('analysis.analysisMonth < :beforeMonth', { beforeMonth })
      .orderBy('analysis.analysisMonth', 'DESC')
      .limit(limit)
      .getMany()

    // Reversed to chronological (oldest-first) order — SpendingForecastModel.predict treats
    // array position as the month index.
    return rows.reverse().map((row) => new SpendingAnalysis({
      analysisId: row.analysisId,
      accountId: row.accountId,
      analysisMonth: row.analysisMonth,
      totalAmount: row.totalAmount,
      transactionCount: row.transactionCount,
      averageAmount: row.averageAmount,
      changeFromPreviousMonth: row.changeFromPreviousMonth,
      trend: row.trend as SpendingTrend,
      createdAt: row.createdAt
    }))
  }
}
