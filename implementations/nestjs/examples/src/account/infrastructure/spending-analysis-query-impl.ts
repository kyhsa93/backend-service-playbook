import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { SpendingAnalysisQuery } from '@/account/application/query/spending-analysis-query'
import { SpendingAnalysisResult } from '@/account/application/query/spending-analysis-result'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { SpendingAnalysisEntity } from '@/account/infrastructure/entity/spending-analysis.entity'

@Injectable()
export class SpendingAnalysisQueryImpl extends SpendingAnalysisQuery {
  constructor(
    @InjectRepository(AccountEntity) private readonly accountRepo: Repository<AccountEntity>,
    @InjectRepository(SpendingAnalysisEntity) private readonly analysisRepo: Repository<SpendingAnalysisEntity>
  ) {
    super()
  }

  public async getAnalysis(query: {
    accountId: string
    ownerId: string
    analysisMonth: string
  }): Promise<SpendingAnalysisResult> {
    const account = await this.accountRepo.createQueryBuilder('account')
      .where('account.accountId = :accountId', { accountId: query.accountId })
      .andWhere('account.ownerId = :ownerId', { ownerId: query.ownerId })
      .getOne()
    if (!account) throw new Error(ErrorMessage['Account not found.'])

    const row = await this.analysisRepo.createQueryBuilder('analysis')
      .where('analysis.accountId = :accountId', { accountId: query.accountId })
      .andWhere('analysis.analysisMonth = :analysisMonth', { analysisMonth: query.analysisMonth })
      .getOne()
    if (!row) throw new Error(ErrorMessage['Spending analysis not found.'])

    return {
      analysisMonth: row.analysisMonth,
      totalAmount: row.totalAmount,
      transactionCount: row.transactionCount,
      averageAmount: row.averageAmount,
      changeFromPreviousMonth: row.changeFromPreviousMonth,
      trend: row.trend,
      createdAt: row.createdAt
    }
  }
}
