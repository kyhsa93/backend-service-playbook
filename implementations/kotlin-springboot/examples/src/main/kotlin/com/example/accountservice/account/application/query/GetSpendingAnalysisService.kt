package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.SpendingAnalysisNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Serves the read-model row `AnalyzeMonthlySpendingService` (the ETL) already precomputed — this
 * Service does no aggregation of its own, it only looks the row up. Account (like Refund) has an
 * ownerId reachable in a single lookup, so ownership is verified via `AccountQuery.findAccounts`
 * first, the same pattern `GetAccountService`/`GetTransactionsService` use.
 */
@Service
@Transactional(readOnly = true)
class GetSpendingAnalysisService(
    private val accountQuery: AccountQuery,
    private val spendingAnalysisQuery: SpendingAnalysisQuery,
) {
    fun getSpendingAnalysis(
        accountId: String,
        requesterId: String,
        month: LocalDate,
    ): GetSpendingAnalysisResult {
        val (accounts, _) =
            accountQuery.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId),
            )
        accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)

        val analysisMonth = "%04d-%02d".format(month.year, month.monthValue)
        val analyses = spendingAnalysisQuery.findAnalyses(SpendingAnalysisFindQuery(accountId, analysisMonth))
        val analysis = analyses.firstOrNull() ?: throw SpendingAnalysisNotFoundException(accountId, analysisMonth)

        return GetSpendingAnalysisResult(
            analysisMonth = analysis.analysisMonth,
            totalAmount = analysis.totalAmount,
            transactionCount = analysis.transactionCount,
            averageAmount = analysis.averageAmount,
            changeFromPreviousMonth = analysis.changeFromPreviousMonth,
            trend = analysis.trend.name,
            createdAt = analysis.createdAt,
        )
    }
}
