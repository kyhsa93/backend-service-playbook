import { generateId } from '@/common/generate-id'

export type ForecastConfidence = 'LOW' | 'MEDIUM' | 'HIGH'

// A materialized read-model row — the ETL's precomputed answer to "what will this account
// likely spend next month," produced monthly by ForecastSpendingCommandHandler (which trains
// SpendingForecastModel fresh from the account's spending_analysis history on every run) and
// served as-is by GetSpendingForecastQueryHandler. No business invariant lives here — the one
// real transform step (fitting the model) already happened before this object is constructed —
// so this stays a plain data holder, the same reasoning as SpendingAnalysis.
export class SpendingForecast {
  public readonly forecastId: string
  public readonly accountId: string
  public readonly forecastMonth: string
  public readonly predictedAmount: number
  public readonly confidence: ForecastConfidence
  public readonly historyMonthsUsed: number
  public readonly createdAt: Date

  constructor(params: {
    forecastId?: string
    accountId: string
    forecastMonth: string
    predictedAmount: number
    confidence: ForecastConfidence
    historyMonthsUsed: number
    createdAt?: Date
  }) {
    this.forecastId = params.forecastId ?? generateId()
    this.accountId = params.accountId
    this.forecastMonth = params.forecastMonth
    this.predictedAmount = params.predictedAmount
    this.confidence = params.confidence
    this.historyMonthsUsed = params.historyMonthsUsed
    this.createdAt = params.createdAt ?? new Date()
  }
}
