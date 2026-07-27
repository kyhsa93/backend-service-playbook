import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { SpendingAnalysis } from '@/account/domain/spending-analysis'
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
}
