import { SpendingAnalysisResult } from '@/account/application/query/spending-analysis-result'

// Account (like Refund) has an ownerId column directly on it, so ownership is verified in a
// single join against AccountEntity — simpler than Payment/Refund's two-hop verification.
export abstract class SpendingAnalysisQuery {
  abstract getAnalysis(query: {
    accountId: string
    ownerId: string
    analysisMonth: string
  }): Promise<SpendingAnalysisResult>
}
