import { generateId } from '@/common/generate-id'

export type SpendingTrend = 'INCREASING' | 'DECREASING' | 'STABLE'

const TREND_THRESHOLD_PERCENT = 10

// A materialized read-model row — the ETL's precomputed answer to "how did this account's
// spending change this month," produced monthly by AnalyzeMonthlySpendingCommandHandler and
// served as-is by GetSpendingAnalysisQueryHandler. No business invariant lives here beyond the
// one real "transform" step (turning two raw totals into a %-change and a trend label), so
// this stays a plain data holder rather than a stateful Aggregate.
export class SpendingAnalysis {
  public readonly analysisId: string
  public readonly accountId: string
  public readonly analysisMonth: string
  public readonly totalAmount: number
  public readonly transactionCount: number
  public readonly averageAmount: number
  public readonly changeFromPreviousMonth: number
  public readonly trend: SpendingTrend
  public readonly createdAt: Date

  constructor(params: {
    analysisId?: string
    accountId: string
    analysisMonth: string
    totalAmount: number
    transactionCount: number
    averageAmount: number
    changeFromPreviousMonth: number
    trend: SpendingTrend
    createdAt?: Date
  }) {
    this.analysisId = params.analysisId ?? generateId()
    this.accountId = params.accountId
    this.analysisMonth = params.analysisMonth
    this.totalAmount = params.totalAmount
    this.transactionCount = params.transactionCount
    this.averageAmount = params.averageAmount
    this.changeFromPreviousMonth = params.changeFromPreviousMonth
    this.trend = params.trend
    this.createdAt = params.createdAt ?? new Date()
  }

  // previousTotalAmount is always a real computed sum (0 when the account had no withdrawals
  // last month, never null) — there's no "unknown baseline" case to special-case, since a
  // brand-new account with no prior-month history genuinely did spend 0 that month.
  public static create(params: {
    accountId: string
    analysisMonth: string
    totalAmount: number
    transactionCount: number
    previousTotalAmount: number
  }): SpendingAnalysis {
    const averageAmount = params.transactionCount > 0 ? Math.round(params.totalAmount / params.transactionCount) : 0

    const changeFromPreviousMonth = params.previousTotalAmount === 0
      ? (params.totalAmount === 0 ? 0 : 100)
      : Math.round(((params.totalAmount - params.previousTotalAmount) / params.previousTotalAmount) * 100)

    let trend: SpendingTrend = 'STABLE'
    if (changeFromPreviousMonth > TREND_THRESHOLD_PERCENT) trend = 'INCREASING'
    else if (changeFromPreviousMonth < -TREND_THRESHOLD_PERCENT) trend = 'DECREASING'

    return new SpendingAnalysis({
      accountId: params.accountId,
      analysisMonth: params.analysisMonth,
      totalAmount: params.totalAmount,
      transactionCount: params.transactionCount,
      averageAmount,
      changeFromPreviousMonth,
      trend
    })
  }
}
