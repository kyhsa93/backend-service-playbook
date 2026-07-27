import { SpendingAnalysis } from '@/account/domain/spending-analysis'

export abstract class SpendingAnalysisRepository {
  abstract saveAnalysis(analysis: SpendingAnalysis): Promise<void>

  // A cheap idempotency check ahead of the real work — the (accountId, analysisMonth) unique
  // constraint on the table is the last line of defense, the same two-layer pattern as
  // CardStatementNotificationService.hasSentStatement.
  abstract hasAnalysis(accountId: string, analysisMonth: string): Promise<boolean>
}
