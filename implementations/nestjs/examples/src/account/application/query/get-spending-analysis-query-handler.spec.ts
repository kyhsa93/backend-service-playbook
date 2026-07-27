import { Test } from '@nestjs/testing'

import { GetSpendingAnalysisQuery } from '@/account/application/query/get-spending-analysis-query'
import { GetSpendingAnalysisQueryHandler } from '@/account/application/query/get-spending-analysis-query-handler'
import { SpendingAnalysisQuery } from '@/account/application/query/spending-analysis-query'

describe('GetSpendingAnalysisQueryHandler', () => {
  let handler: GetSpendingAnalysisQueryHandler
  let spendingAnalysisQuery: jest.Mocked<SpendingAnalysisQuery>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        GetSpendingAnalysisQueryHandler,
        { provide: SpendingAnalysisQuery, useValue: { getAnalysis: jest.fn() } }
      ]
    }).compile()

    handler = module.get(GetSpendingAnalysisQueryHandler)
    spendingAnalysisQuery = module.get(SpendingAnalysisQuery)
  })

  it('execute_when_called_then_queries_by_ownerId_and_derives_analysisMonth_from_the_month_param', async () => {
    const expected = {
      analysisMonth: '2026-07', totalAmount: 15000, transactionCount: 2, averageAmount: 7500,
      changeFromPreviousMonth: 50, trend: 'INCREASING', createdAt: new Date()
    }
    spendingAnalysisQuery.getAnalysis.mockResolvedValue(expected)

    const result = await handler.execute(
      new GetSpendingAnalysisQuery({ accountId: 'account-1', requesterId: 'owner-1', month: '2026-07-01' })
    )

    expect(spendingAnalysisQuery.getAnalysis).toHaveBeenCalledWith({
      accountId: 'account-1', ownerId: 'owner-1', analysisMonth: '2026-07'
    })
    expect(result).toBe(expected)
  })
})
