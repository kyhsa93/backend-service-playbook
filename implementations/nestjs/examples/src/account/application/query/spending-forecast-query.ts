import { SpendingForecastResult } from '@/account/application/query/spending-forecast-result'

// Account (like Refund) has an ownerId column directly on it, so ownership is verified in a
// single join against AccountEntity — the same reasoning as SpendingAnalysisQuery.
export abstract class SpendingForecastQuery {
  abstract getForecast(query: {
    accountId: string
    ownerId: string
    forecastMonth: string
  }): Promise<SpendingForecastResult>
}
