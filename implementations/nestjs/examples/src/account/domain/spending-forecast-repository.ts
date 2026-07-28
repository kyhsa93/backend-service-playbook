import { SpendingForecast } from '@/account/domain/spending-forecast'

export abstract class SpendingForecastRepository {
  abstract saveForecast(forecast: SpendingForecast): Promise<void>

  // A cheap idempotency check ahead of the real work — the (accountId, forecastMonth) unique
  // constraint on the table is the last line of defense, the same two-layer pattern as
  // SpendingAnalysisRepository.hasAnalysis.
  abstract hasForecast(accountId: string, forecastMonth: string): Promise<boolean>
}
