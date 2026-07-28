import { SpendingAnalysis } from '@/account/domain/spending-analysis'

export abstract class SpendingAnalysisRepository {
  abstract saveAnalysis(analysis: SpendingAnalysis): Promise<void>

  // A cheap idempotency check ahead of the real work — the (accountId, analysisMonth) unique
  // constraint on the table is the last line of defense, the same two-layer pattern as
  // CardStatementNotificationService.hasSentStatement.
  abstract hasAnalysis(accountId: string, analysisMonth: string): Promise<boolean>

  // The training data for ForecastSpendingCommandHandler — every analysis row strictly before
  // beforeMonth, most-recent-first, capped at `limit`. Returned oldest-first (the reverse of
  // the query order) since SpendingForecastModel.predict expects chronological input.
  abstract findRecentAnalyses(accountId: string, beforeMonth: string, limit: number): Promise<SpendingAnalysis[]>
}
