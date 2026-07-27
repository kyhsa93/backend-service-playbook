package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.SpendingAnalysis
import com.example.accountservice.account.domain.SpendingAnalysisRepository
import com.example.accountservice.account.domain.TransactionSummaryQuery
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * The monthly spending-analysis ETL — called by
 * [com.example.accountservice.account.interfaces.task.AnalyzeMonthlySpendingTaskController] (the Task
 * Queue's `account.analyze-monthly-spending` handler). Like `PayInterestService`/
 * `SendMonthlyCardStatementsService`, this is a batch job triggered by the system (Scheduler) rather
 * than a Command the user requests directly, so it takes plain date/String parameters instead of a
 * dedicated `*Command` DTO — the same reasoning `PayInterestService`'s KDoc gives.
 *
 * The ETL, in full: Extract (paginate every ACTIVE account, summarize its and the prior month's
 * WITHDRAWAL transactions), Transform ([SpendingAnalysis.create]'s %-change/trend calculation), Load
 * (one row per account per month into `spending_analysis`). The output is a queryable read-model row,
 * not a file — the value is precomputing an aggregate a client would otherwise have to re-derive from
 * potentially many raw Transaction rows on every request.
 *
 * Unlike `PayInterestService` (a single bounded `findAccounts(take = 10_000)` call),
 * this walks every page of ACTIVE accounts explicitly with [PAGE_SIZE] = 100 — the account universe
 * this ETL must cover isn't bounded the way a single day's interest run is, so it keeps paginating
 * until an empty page signals the end.
 */
@Service
class AnalyzeMonthlySpendingService(
    private val accountRepository: AccountRepository,
    private val spendingAnalysisRepository: SpendingAnalysisRepository,
) {
    fun analyzeMonthlySpending(
        analysisMonth: String,
        monthStart: LocalDateTime,
        monthEnd: LocalDateTime,
        previousMonthStart: LocalDateTime,
        previousMonthEnd: LocalDateTime,
    ): Int {
        var analyzedCount = 0
        var page = 0

        while (true) {
            val (accounts, _) =
                accountRepository.findAccounts(
                    AccountFindQuery(page = page, take = PAGE_SIZE, status = listOf(AccountStatus.ACTIVE.name)),
                )
            if (accounts.isEmpty()) break

            for (account in accounts) {
                if (spendingAnalysisRepository.hasAnalysis(account.accountId, analysisMonth)) continue

                val current =
                    accountRepository.summarizeTransactions(
                        TransactionSummaryQuery(
                            accountId = account.accountId,
                            type = listOf(TransactionType.WITHDRAWAL),
                            createdAtFrom = monthStart,
                            createdAtTo = monthEnd,
                        ),
                    )
                val previous =
                    accountRepository.summarizeTransactions(
                        TransactionSummaryQuery(
                            accountId = account.accountId,
                            type = listOf(TransactionType.WITHDRAWAL),
                            createdAtFrom = previousMonthStart,
                            createdAtTo = previousMonthEnd,
                        ),
                    )

                val analysis =
                    SpendingAnalysis.create(
                        accountId = account.accountId,
                        analysisMonth = analysisMonth,
                        totalAmount = current.totalAmount,
                        transactionCount = current.count,
                        previousTotalAmount = previous.totalAmount,
                    )
                spendingAnalysisRepository.saveAnalysis(analysis)
                analyzedCount++
            }

            page++
        }

        return analyzedCount
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
