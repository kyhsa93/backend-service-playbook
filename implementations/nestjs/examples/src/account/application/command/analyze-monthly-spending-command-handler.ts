import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { AnalyzeMonthlySpendingCommand } from '@/account/application/command/analyze-monthly-spending-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { SpendingAnalysis } from '@/account/domain/spending-analysis'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { AccountStatus } from '@/account/account-enum'

const PAGE_SIZE = 100

// The Command the account.analyze-monthly-spending Task Controller delegates to. The ETL, in
// full: Extract (paginate every ACTIVE account, summarize its and the prior month's WITHDRAWAL
// transactions), Transform (SpendingAnalysis.create's %-change/trend calculation), Load (one
// row per account per month into spending_analysis). The output is a queryable read-model row,
// not a file — the value is precomputing an aggregate a client would otherwise have to
// re-derive from potentially many raw Transaction rows on every request.
@CommandHandler(AnalyzeMonthlySpendingCommand)
export class AnalyzeMonthlySpendingCommandHandler implements ICommandHandler<AnalyzeMonthlySpendingCommand, number> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly spendingAnalysisRepository: SpendingAnalysisRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: AnalyzeMonthlySpendingCommand): Promise<number> {
    let analyzedCount = 0
    let page = 0

    while (true) {
      const { accounts } = await this.accountRepository.findAccounts({
        status: [AccountStatus.ACTIVE],
        take: PAGE_SIZE,
        page
      })
      if (accounts.length === 0) break

      for (const account of accounts) {
        const alreadyAnalyzed = await this.spendingAnalysisRepository.hasAnalysis(account.accountId, command.analysisMonth)
        if (alreadyAnalyzed) continue

        const [current, previous] = await Promise.all([
          this.accountRepository.summarizeTransactions({
            accountId: account.accountId,
            type: ['WITHDRAWAL'],
            createdAtFrom: command.monthStart,
            createdAtTo: command.monthEnd
          }),
          this.accountRepository.summarizeTransactions({
            accountId: account.accountId,
            type: ['WITHDRAWAL'],
            createdAtFrom: command.previousMonthStart,
            createdAtTo: command.previousMonthEnd
          })
        ])

        const analysis = SpendingAnalysis.create({
          accountId: account.accountId,
          analysisMonth: command.analysisMonth,
          totalAmount: current.totalAmount,
          transactionCount: current.count,
          previousTotalAmount: previous.totalAmount
        })

        await this.transactionManager.run(async () => {
          await this.spendingAnalysisRepository.saveAnalysis(analysis)
        })
        analyzedCount++
      }

      page++
    }

    return analyzedCount
  }
}
