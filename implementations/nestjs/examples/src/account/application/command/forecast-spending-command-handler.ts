import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { ForecastSpendingCommand } from '@/account/application/command/forecast-spending-command'
import { SpendingForecastModel } from '@/account/application/service/spending-forecast-model'
import { AccountRepository } from '@/account/domain/account-repository'
import { SpendingAnalysisRepository } from '@/account/domain/spending-analysis-repository'
import { SpendingForecast } from '@/account/domain/spending-forecast'
import { SpendingForecastRepository } from '@/account/domain/spending-forecast-repository'
import { AccountStatus } from '@/account/account-enum'

const PAGE_SIZE = 100

// A cold-start guard, not a tuning knob: 2 points make any line "fit" perfectly (rSquared === 1
// regardless of the actual trend), so 3 is the minimum for SpendingForecastModel's R² to mean
// anything. An account younger than 3 analyzed months is simply skipped and retried by next
// month's run once it has more history — the same "skip, don't fail" idempotency-adjacent
// posture as AnalyzeMonthlySpendingCommandHandler skipping an already-analyzed account.
const MIN_HISTORY_MONTHS_FOR_FORECAST = 3
const MAX_HISTORY_MONTHS_FOR_FORECAST = 6

// The Command the account.forecast-spending Task Controller delegates to. Trains (fits) a
// fresh SpendingForecastModel per account from that account's own spending_analysis history on
// every run — there's no persisted "model weights" row separate from the forecast itself, the
// same simplicity tradeoff the ETL job upstream makes (recomputed monthly, not maintained
// incrementally). Output is a queryable read-model row, not a file.
@CommandHandler(ForecastSpendingCommand)
export class ForecastSpendingCommandHandler implements ICommandHandler<ForecastSpendingCommand, number> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly spendingAnalysisRepository: SpendingAnalysisRepository,
    private readonly spendingForecastRepository: SpendingForecastRepository,
    private readonly spendingForecastModel: SpendingForecastModel,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: ForecastSpendingCommand): Promise<number> {
    let forecastedCount = 0
    let page = 0

    while (true) {
      const { accounts } = await this.accountRepository.findAccounts({
        status: [AccountStatus.ACTIVE],
        take: PAGE_SIZE,
        page
      })
      if (accounts.length === 0) break

      for (const account of accounts) {
        const alreadyForecasted = await this.spendingForecastRepository.hasForecast(account.accountId, command.forecastMonth)
        if (alreadyForecasted) continue

        const history = await this.spendingAnalysisRepository.findRecentAnalyses(
          account.accountId,
          command.forecastMonth,
          MAX_HISTORY_MONTHS_FOR_FORECAST
        )
        if (history.length < MIN_HISTORY_MONTHS_FOR_FORECAST) continue

        const prediction = this.spendingForecastModel.predict(
          history.map((analysis) => ({ analysisMonth: analysis.analysisMonth, totalAmount: analysis.totalAmount }))
        )

        const forecast = new SpendingForecast({
          accountId: account.accountId,
          forecastMonth: command.forecastMonth,
          predictedAmount: prediction.predictedAmount,
          confidence: prediction.confidence,
          historyMonthsUsed: history.length
        })

        await this.transactionManager.run(async () => {
          await this.spendingForecastRepository.saveForecast(forecast)
        })
        forecastedCount++
      }

      page++
    }

    return forecastedCount
  }
}
