package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.SpendingForecastNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Serves the read-model row `ForecastSpendingService` (the batch job) already precomputed — this
 * Service does no training of its own, it only looks the row up. Account (like Refund) has an
 * ownerId reachable in a single lookup, so ownership is verified via `AccountQuery.findAccounts`
 * first, the same pattern `GetSpendingAnalysisService` uses.
 */
@Service
@Transactional(readOnly = true)
class GetSpendingForecastService(
    private val accountQuery: AccountQuery,
    private val spendingForecastQuery: SpendingForecastQuery,
) {
    fun getSpendingForecast(
        accountId: String,
        requesterId: String,
        month: LocalDate,
    ): GetSpendingForecastResult {
        val (accounts, _) =
            accountQuery.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId),
            )
        accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)

        val forecastMonth = "%04d-%02d".format(month.year, month.monthValue)
        val forecasts = spendingForecastQuery.findForecasts(SpendingForecastFindQuery(accountId, forecastMonth))
        val forecast = forecasts.firstOrNull() ?: throw SpendingForecastNotFoundException(accountId, forecastMonth)

        return GetSpendingForecastResult(
            forecastMonth = forecast.forecastMonth,
            predictedAmount = forecast.predictedAmount,
            confidence = forecast.confidence.name,
            historyMonthsUsed = forecast.historyMonthsUsed,
            createdAt = forecast.createdAt,
        )
    }
}
