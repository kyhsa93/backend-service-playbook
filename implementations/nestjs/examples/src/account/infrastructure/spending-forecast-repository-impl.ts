import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { SpendingForecast } from '@/account/domain/spending-forecast'
import { SpendingForecastRepository } from '@/account/domain/spending-forecast-repository'
import { SpendingForecastEntity } from '@/account/infrastructure/entity/spending-forecast.entity'

@Injectable()
export class SpendingForecastRepositoryImpl extends SpendingForecastRepository {
  constructor(private readonly transactionManager: TransactionManager) {
    super()
  }

  public async saveForecast(forecast: SpendingForecast): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(SpendingForecastEntity, {
      forecastId: forecast.forecastId,
      accountId: forecast.accountId,
      forecastMonth: forecast.forecastMonth,
      predictedAmount: forecast.predictedAmount,
      confidence: forecast.confidence,
      historyMonthsUsed: forecast.historyMonthsUsed,
      createdAt: forecast.createdAt
    })
  }

  public async hasForecast(accountId: string, forecastMonth: string): Promise<boolean> {
    const manager = this.transactionManager.getManager()
    const count = await manager.count(SpendingForecastEntity, { where: { accountId, forecastMonth } })
    return count > 0
  }
}
