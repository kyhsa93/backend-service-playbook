import { Test } from '@nestjs/testing'

import { TransactionManager } from '@/database/transaction-manager'
import { ForecastSpendingCommand } from '@/account/application/command/forecast-spending-command'
import { ForecastSpendingCommandHandler } from '@/account/application/command/forecast-spending-command-handler'
import { SpendingForecastModel } from '@/account/application/service/spending-forecast-model'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { SpendingAnalysis } from '@/account/domain/spending-analysis'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { SpendingForecastRepository } from '@/account/domain/spending-forecast-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('ForecastSpendingCommandHandler', () => {
  let handler: ForecastSpendingCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>
  let spendingAnalysisRepository: jest.Mocked<SpendingAnalysisRepository>
  let spendingForecastRepository: jest.Mocked<SpendingForecastRepository>
  let spendingForecastModel: jest.Mocked<SpendingForecastModel>

  const account = new Account({
    accountId: 'account-1', ownerId: 'owner-1', email: 'a@example.com',
    balance: new Money({ amount: 50000, currency: 'KRW' }), status: AccountStatus.ACTIVE
  })

  const command = new ForecastSpendingCommand({ forecastMonth: '2026-07' })

  const threeMonthsHistory = [
    new SpendingAnalysis({
      accountId: 'account-1', analysisMonth: '2026-04', totalAmount: 10000,
      transactionCount: 1, averageAmount: 10000, changeFromPreviousMonth: 100, trend: 'INCREASING'
    }),
    new SpendingAnalysis({
      accountId: 'account-1', analysisMonth: '2026-05', totalAmount: 20000,
      transactionCount: 1, averageAmount: 20000, changeFromPreviousMonth: 100, trend: 'INCREASING'
    }),
    new SpendingAnalysis({
      accountId: 'account-1', analysisMonth: '2026-06', totalAmount: 30000,
      transactionCount: 1, averageAmount: 30000, changeFromPreviousMonth: 50, trend: 'INCREASING'
    })
  ]

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        ForecastSpendingCommandHandler,
        { provide: AccountRepository, useValue: { findAccounts: jest.fn(), saveAccount: jest.fn(), summarizeTransactions: jest.fn(), hasTransactionWithReference: jest.fn() } },
        { provide: SpendingAnalysisRepository, useValue: { saveAnalysis: jest.fn(), hasAnalysis: jest.fn(), findRecentAnalyses: jest.fn() } },
        { provide: SpendingForecastRepository, useValue: { saveForecast: jest.fn(), hasForecast: jest.fn() } },
        { provide: SpendingForecastModel, useValue: { predict: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(ForecastSpendingCommandHandler)
    accountRepository = module.get(AccountRepository)
    spendingAnalysisRepository = module.get(SpendingAnalysisRepository)
    spendingForecastRepository = module.get(SpendingForecastRepository)
    spendingForecastModel = module.get(SpendingForecastModel)
  })

  it('execute_when_an_account_has_at_least_3_months_of_history_and_no_forecast_yet_then_trains_and_saves_a_forecast', async () => {
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [account], count: 1 })
      .mockResolvedValueOnce({ accounts: [], count: 1 })
    spendingForecastRepository.hasForecast.mockResolvedValue(false)
    spendingAnalysisRepository.findRecentAnalyses.mockResolvedValue(threeMonthsHistory)
    spendingForecastModel.predict.mockReturnValue({ predictedAmount: 40000, confidence: 'HIGH' })

    const forecastedCount = await handler.execute(command)

    expect(spendingAnalysisRepository.findRecentAnalyses).toHaveBeenCalledWith('account-1', '2026-07', 6)
    expect(spendingForecastModel.predict).toHaveBeenCalledWith([
      { analysisMonth: '2026-04', totalAmount: 10000 },
      { analysisMonth: '2026-05', totalAmount: 20000 },
      { analysisMonth: '2026-06', totalAmount: 30000 }
    ])
    expect(spendingForecastRepository.saveForecast).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: 'account-1', forecastMonth: '2026-07', predictedAmount: 40000, confidence: 'HIGH', historyMonthsUsed: 3
      })
    )
    expect(forecastedCount).toBe(1)
  })

  it('execute_when_an_account_has_fewer_than_3_months_of_history_then_skips_it_without_training', async () => {
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [account], count: 1 })
      .mockResolvedValueOnce({ accounts: [], count: 1 })
    spendingForecastRepository.hasForecast.mockResolvedValue(false)
    spendingAnalysisRepository.findRecentAnalyses.mockResolvedValue(threeMonthsHistory.slice(0, 2))

    const forecastedCount = await handler.execute(command)

    expect(spendingForecastModel.predict).not.toHaveBeenCalled()
    expect(spendingForecastRepository.saveForecast).not.toHaveBeenCalled()
    expect(forecastedCount).toBe(0)
  })

  it('execute_when_an_account_already_has_a_forecast_for_the_month_then_skips_it', async () => {
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [account], count: 1 })
      .mockResolvedValueOnce({ accounts: [], count: 1 })
    spendingForecastRepository.hasForecast.mockResolvedValue(true)

    const forecastedCount = await handler.execute(command)

    expect(spendingAnalysisRepository.findRecentAnalyses).not.toHaveBeenCalled()
    expect(spendingForecastRepository.saveForecast).not.toHaveBeenCalled()
    expect(forecastedCount).toBe(0)
  })
})
