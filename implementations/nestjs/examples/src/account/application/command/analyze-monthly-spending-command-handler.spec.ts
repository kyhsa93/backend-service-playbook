import { Test } from '@nestjs/testing'

import { TransactionManager } from '@/database/transaction-manager'
import { AnalyzeMonthlySpendingCommand } from '@/account/application/command/analyze-monthly-spending-command'
import { AnalyzeMonthlySpendingCommandHandler } from '@/account/application/command/analyze-monthly-spending-command-handler'
import { Account } from '@/account/domain/account'
import { AccountRepository } from '@/account/domain/account-repository'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { AccountStatus } from '@/account/account-enum'
import { Money } from '@/account/domain/money'

describe('AnalyzeMonthlySpendingCommandHandler', () => {
  let handler: AnalyzeMonthlySpendingCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>
  let spendingAnalysisRepository: jest.Mocked<SpendingAnalysisRepository>

  const account = new Account({
    accountId: 'account-1', ownerId: 'owner-1', email: 'a@example.com',
    balance: new Money({ amount: 50000, currency: 'KRW' }), status: AccountStatus.ACTIVE
  })

  const command = new AnalyzeMonthlySpendingCommand({
    analysisMonth: '2026-07',
    monthStart: new Date('2026-07-01T00:00:00.000Z'),
    monthEnd: new Date('2026-08-01T00:00:00.000Z'),
    previousMonthStart: new Date('2026-06-01T00:00:00.000Z'),
    previousMonthEnd: new Date('2026-07-01T00:00:00.000Z')
  })

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        AnalyzeMonthlySpendingCommandHandler,
        { provide: AccountRepository, useValue: { findAccounts: jest.fn(), saveAccount: jest.fn(), summarizeTransactions: jest.fn(), hasTransactionWithReference: jest.fn() } },
        { provide: SpendingAnalysisRepository, useValue: { saveAnalysis: jest.fn(), hasAnalysis: jest.fn() } },
        { provide: TransactionManager, useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() } }
      ]
    }).compile()

    handler = module.get(AnalyzeMonthlySpendingCommandHandler)
    accountRepository = module.get(AccountRepository)
    spendingAnalysisRepository = module.get(SpendingAnalysisRepository)
  })

  it('execute_when_an_account_has_not_been_analyzed_yet_then_summarizes_both_months_and_saves_the_analysis', async () => {
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [account], count: 1 })
      .mockResolvedValueOnce({ accounts: [], count: 1 })
    spendingAnalysisRepository.hasAnalysis.mockResolvedValue(false)
    accountRepository.summarizeTransactions
      .mockResolvedValueOnce({ count: 2, totalAmount: 15000 }) // current month
      .mockResolvedValueOnce({ count: 1, totalAmount: 10000 }) // previous month

    const analyzedCount = await handler.execute(command)

    expect(accountRepository.findAccounts).toHaveBeenCalledWith({ status: [AccountStatus.ACTIVE], take: 100, page: 0 })
    expect(accountRepository.summarizeTransactions).toHaveBeenCalledWith({
      accountId: 'account-1', type: ['WITHDRAWAL'], createdAtFrom: command.monthStart, createdAtTo: command.monthEnd
    })
    expect(accountRepository.summarizeTransactions).toHaveBeenCalledWith({
      accountId: 'account-1', type: ['WITHDRAWAL'], createdAtFrom: command.previousMonthStart, createdAtTo: command.previousMonthEnd
    })
    expect(spendingAnalysisRepository.saveAnalysis).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'account-1', analysisMonth: '2026-07', totalAmount: 15000, transactionCount: 2, trend: 'INCREASING' })
    )
    expect(analyzedCount).toBe(1)
  })

  it('execute_when_an_account_was_already_analyzed_this_month_then_skips_it', async () => {
    accountRepository.findAccounts
      .mockResolvedValueOnce({ accounts: [account], count: 1 })
      .mockResolvedValueOnce({ accounts: [], count: 1 })
    spendingAnalysisRepository.hasAnalysis.mockResolvedValue(true)

    const analyzedCount = await handler.execute(command)

    expect(accountRepository.summarizeTransactions).not.toHaveBeenCalled()
    expect(spendingAnalysisRepository.saveAnalysis).not.toHaveBeenCalled()
    expect(analyzedCount).toBe(0)
  })

  it('execute_when_there_are_no_active_accounts_then_returns_0_without_summarizing', async () => {
    accountRepository.findAccounts.mockResolvedValueOnce({ accounts: [], count: 0 })

    const analyzedCount = await handler.execute(command)

    expect(analyzedCount).toBe(0)
    expect(spendingAnalysisRepository.saveAnalysis).not.toHaveBeenCalled()
  })
})
