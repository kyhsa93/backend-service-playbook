import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetSpendingAnalysisQuery } from '@/account/application/query/get-spending-analysis-query'
import { SpendingAnalysisQuery } from '@/account/application/query/spending-analysis-query'
import { SpendingAnalysisResult } from '@/account/application/query/spending-analysis-result'

@QueryHandler(GetSpendingAnalysisQuery)
export class GetSpendingAnalysisQueryHandler implements IQueryHandler<GetSpendingAnalysisQuery, SpendingAnalysisResult> {
  constructor(private readonly spendingAnalysisQuery: SpendingAnalysisQuery) {}

  public async execute(query: GetSpendingAnalysisQuery): Promise<SpendingAnalysisResult> {
    return this.spendingAnalysisQuery.getAnalysis({
      accountId: query.accountId,
      ownerId: query.requesterId,
      analysisMonth: query.analysisMonth
    })
  }
}
