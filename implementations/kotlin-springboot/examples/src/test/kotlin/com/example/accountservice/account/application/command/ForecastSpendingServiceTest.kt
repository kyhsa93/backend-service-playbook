package com.example.accountservice.account.application.command

import com.example.accountservice.account.application.service.SpendingForecastModel
import com.example.accountservice.account.application.service.SpendingForecastPrediction
import com.example.accountservice.account.application.service.SpendingHistoryPoint
import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.ForecastConfidence
import com.example.accountservice.account.domain.SpendingAnalysis
import com.example.accountservice.account.domain.SpendingAnalysisRepository
import com.example.accountservice.account.domain.SpendingForecastRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForecastSpendingServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val spendingAnalysisRepository = mockk<SpendingAnalysisRepository>()
    private val spendingForecastRepository = mockk<SpendingForecastRepository>(relaxed = true)
    private val spendingForecastModel = mockk<SpendingForecastModel>()
    private val service =
        ForecastSpendingService(accountRepository, spendingAnalysisRepository, spendingForecastRepository, spendingForecastModel)

    private val account = Account.create("owner-1", "KRW", "owner-1@example.com")
    private val forecastMonth = "2026-07"

    private val threeMonthsHistory =
        listOf(
            SpendingAnalysis.create(account.accountId, "2026-04", 10000, 1, 0),
            SpendingAnalysis.create(account.accountId, "2026-05", 20000, 1, 10000),
            SpendingAnalysis.create(account.accountId, "2026-06", 30000, 1, 20000),
        )

    @Test
    fun `forecastSpending when an account has at least 3 months of history and no forecast yet then trains and saves a forecast`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (listOf(account) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 1, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 1L)
        every { spendingForecastRepository.hasForecast(account.accountId, forecastMonth) } returns false
        every { spendingAnalysisRepository.findRecentAnalyses(account.accountId, forecastMonth, 6) } returns threeMonthsHistory
        every { spendingForecastModel.predict(any()) } returns SpendingForecastPrediction(40000, ForecastConfidence.HIGH)

        val forecastedCount = service.forecastSpending(forecastMonth)

        verify(exactly = 1) {
            spendingForecastModel.predict(
                listOf(
                    SpendingHistoryPoint("2026-04", 10000),
                    SpendingHistoryPoint("2026-05", 20000),
                    SpendingHistoryPoint("2026-06", 30000),
                ),
            )
        }
        verify(exactly = 1) {
            spendingForecastRepository.saveForecast(
                match {
                    it.accountId == account.accountId &&
                        it.forecastMonth == forecastMonth &&
                        it.predictedAmount == 40000L &&
                        it.confidence == ForecastConfidence.HIGH &&
                        it.historyMonthsUsed == 3
                },
            )
        }
        assertThat(forecastedCount).isEqualTo(1)
    }

    @Test
    fun `forecastSpending when an account has fewer than 3 months of history then skips it without training`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (listOf(account) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 1, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 1L)
        every { spendingForecastRepository.hasForecast(account.accountId, forecastMonth) } returns false
        every {
            spendingAnalysisRepository.findRecentAnalyses(account.accountId, forecastMonth, 6)
        } returns threeMonthsHistory.take(2)

        val forecastedCount = service.forecastSpending(forecastMonth)

        verify(exactly = 0) { spendingForecastModel.predict(any()) }
        verify(exactly = 0) { spendingForecastRepository.saveForecast(any()) }
        assertThat(forecastedCount).isEqualTo(0)
    }

    @Test
    fun `forecastSpending when an account already has a forecast for the month then skips it`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (listOf(account) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 1, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 1L)
        every { spendingForecastRepository.hasForecast(account.accountId, forecastMonth) } returns true

        val forecastedCount = service.forecastSpending(forecastMonth)

        verify(exactly = 0) { spendingAnalysisRepository.findRecentAnalyses(any(), any(), any()) }
        verify(exactly = 0) { spendingForecastRepository.saveForecast(any()) }
        assertThat(forecastedCount).isEqualTo(0)
    }
}
