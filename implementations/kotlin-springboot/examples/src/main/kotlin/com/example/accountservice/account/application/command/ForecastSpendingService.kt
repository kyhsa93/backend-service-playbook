package com.example.accountservice.account.application.command

import com.example.accountservice.account.application.service.SpendingForecastModel
import com.example.accountservice.account.application.service.SpendingHistoryPoint
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.SpendingAnalysisRepository
import com.example.accountservice.account.domain.SpendingForecast
import com.example.accountservice.account.domain.SpendingForecastRepository
import org.springframework.stereotype.Service

/**
 * The monthly spending-forecast training batch — called by
 * [com.example.accountservice.account.interfaces.task.ForecastSpendingTaskController] (the Task
 * Queue's `account.forecast-spending` handler). Like `AnalyzeMonthlySpendingService`, this is a
 * batch job triggered by the system (Scheduler) rather than a Command the user requests directly,
 * so it takes a plain `forecastMonth` String parameter instead of a dedicated `*Command` DTO.
 *
 * Trains (fits) a fresh [SpendingForecastModel] per account from that account's own
 * spending_analysis history on every run — there is no persisted "model weights" row separate from
 * the forecast itself, the same simplicity tradeoff the ETL job upstream
 * (`AnalyzeMonthlySpendingService`) makes (recomputed monthly, not maintained incrementally).
 * Mirrors `AnalyzeMonthlySpendingService`'s pagination structure exactly.
 */
@Service
class ForecastSpendingService(
    private val accountRepository: AccountRepository,
    private val spendingAnalysisRepository: SpendingAnalysisRepository,
    private val spendingForecastRepository: SpendingForecastRepository,
    private val spendingForecastModel: SpendingForecastModel,
) {
    fun forecastSpending(forecastMonth: String): Int {
        var forecastedCount = 0
        var page = 0

        while (true) {
            val (accounts, _) =
                accountRepository.findAccounts(
                    AccountFindQuery(page = page, take = PAGE_SIZE, status = listOf(AccountStatus.ACTIVE.name)),
                )
            if (accounts.isEmpty()) break

            for (account in accounts) {
                if (spendingForecastRepository.hasForecast(account.accountId, forecastMonth)) continue

                val history =
                    spendingAnalysisRepository.findRecentAnalyses(
                        account.accountId,
                        forecastMonth,
                        MAX_HISTORY_MONTHS_FOR_FORECAST,
                    )
                if (history.size < MIN_HISTORY_MONTHS_FOR_FORECAST) continue

                val prediction =
                    spendingForecastModel.predict(
                        history.map { SpendingHistoryPoint(analysisMonth = it.analysisMonth, totalAmount = it.totalAmount) },
                    )

                val forecast =
                    SpendingForecast.create(
                        accountId = account.accountId,
                        forecastMonth = forecastMonth,
                        predictedAmount = prediction.predictedAmount,
                        confidence = prediction.confidence,
                        historyMonthsUsed = history.size,
                    )
                spendingForecastRepository.saveForecast(forecast)
                forecastedCount++
            }

            page++
        }

        return forecastedCount
    }

    companion object {
        private const val PAGE_SIZE = 100

        // A cold-start guard, not a tuning knob: 2 points make any line "fit" perfectly (R^2 == 1
        // regardless of the actual trend), so 3 is the minimum for SpendingForecastModel's R^2 to
        // mean anything. An account younger than 3 analyzed months is simply skipped and retried by
        // next month's run once it has more history — the same "skip, don't fail"
        // idempotency-adjacent posture as AnalyzeMonthlySpendingService skipping an
        // already-analyzed account.
        private const val MIN_HISTORY_MONTHS_FOR_FORECAST = 3
        private const val MAX_HISTORY_MONTHS_FOR_FORECAST = 6
    }
}
