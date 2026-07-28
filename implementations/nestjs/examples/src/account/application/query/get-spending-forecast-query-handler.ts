import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetSpendingForecastQuery } from '@/account/application/query/get-spending-forecast-query'
import { SpendingForecastQuery } from '@/account/application/query/spending-forecast-query'
import { SpendingForecastResult } from '@/account/application/query/spending-forecast-result'

@QueryHandler(GetSpendingForecastQuery)
export class GetSpendingForecastQueryHandler implements IQueryHandler<GetSpendingForecastQuery, SpendingForecastResult> {
  constructor(private readonly spendingForecastQuery: SpendingForecastQuery) {}

  public async execute(query: GetSpendingForecastQuery): Promise<SpendingForecastResult> {
    return this.spendingForecastQuery.getForecast({
      accountId: query.accountId,
      ownerId: query.requesterId,
      forecastMonth: query.forecastMonth
    })
  }
}
