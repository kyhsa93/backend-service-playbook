import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { SpendingForecastQuery } from '@/account/application/query/spending-forecast-query'
import { SpendingForecastResult } from '@/account/application/query/spending-forecast-result'
import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { SpendingForecastEntity } from '@/account/infrastructure/entity/spending-forecast.entity'

@Injectable()
export class SpendingForecastQueryImpl extends SpendingForecastQuery {
  constructor(
    @InjectRepository(AccountEntity) private readonly accountRepo: Repository<AccountEntity>,
    @InjectRepository(SpendingForecastEntity) private readonly forecastRepo: Repository<SpendingForecastEntity>
  ) {
    super()
  }

  public async getForecast(query: {
    accountId: string
    ownerId: string
    forecastMonth: string
  }): Promise<SpendingForecastResult> {
    const account = await this.accountRepo.createQueryBuilder('account')
      .where('account.accountId = :accountId', { accountId: query.accountId })
      .andWhere('account.ownerId = :ownerId', { ownerId: query.ownerId })
      .getOne()
    if (!account) throw new Error(ErrorMessage['Account not found.'])

    const row = await this.forecastRepo.createQueryBuilder('forecast')
      .where('forecast.accountId = :accountId', { accountId: query.accountId })
      .andWhere('forecast.forecastMonth = :forecastMonth', { forecastMonth: query.forecastMonth })
      .getOne()
    if (!row) throw new Error(ErrorMessage['Spending forecast not found.'])

    return {
      forecastMonth: row.forecastMonth,
      predictedAmount: row.predictedAmount,
      confidence: row.confidence,
      historyMonthsUsed: row.historyMonthsUsed,
      createdAt: row.createdAt
    }
  }
}
