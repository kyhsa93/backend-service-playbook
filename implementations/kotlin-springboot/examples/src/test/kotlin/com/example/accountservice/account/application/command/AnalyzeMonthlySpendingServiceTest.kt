package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.account.domain.SpendingAnalysisRepository
import com.example.accountservice.account.domain.TransactionSummary
import com.example.accountservice.account.domain.TransactionSummaryQuery
import com.example.accountservice.account.domain.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AnalyzeMonthlySpendingServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val spendingAnalysisRepository = mockk<SpendingAnalysisRepository>(relaxed = true)
    private val service = AnalyzeMonthlySpendingService(accountRepository, spendingAnalysisRepository)

    private val account = Account.create("owner-1", "KRW", "owner-1@example.com")

    private val analysisMonth = "2026-07"
    private val monthStart = LocalDateTime.parse("2026-07-01T00:00:00")
    private val monthEnd = LocalDateTime.parse("2026-08-01T00:00:00")
    private val previousMonthStart = LocalDateTime.parse("2026-06-01T00:00:00")
    private val previousMonthEnd = LocalDateTime.parse("2026-07-01T00:00:00")

    @Test
    fun `when an account has not been analyzed yet then summarizes both months and saves the analysis`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (listOf(account) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 1, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 1L)
        every { spendingAnalysisRepository.hasAnalysis(account.accountId, analysisMonth) } returns false
        every {
            accountRepository.summarizeTransactions(
                TransactionSummaryQuery(account.accountId, listOf(TransactionType.WITHDRAWAL), monthStart, monthEnd),
            )
        } returns TransactionSummary(count = 2, totalAmount = 15000)
        every {
            accountRepository.summarizeTransactions(
                TransactionSummaryQuery(account.accountId, listOf(TransactionType.WITHDRAWAL), previousMonthStart, previousMonthEnd),
            )
        } returns TransactionSummary(count = 1, totalAmount = 10000)

        val analyzedCount = service.analyzeMonthlySpending(analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd)

        verify(exactly = 1) {
            accountRepository.summarizeTransactions(
                TransactionSummaryQuery(account.accountId, listOf(TransactionType.WITHDRAWAL), monthStart, monthEnd),
            )
        }
        verify(exactly = 1) {
            accountRepository.summarizeTransactions(
                TransactionSummaryQuery(account.accountId, listOf(TransactionType.WITHDRAWAL), previousMonthStart, previousMonthEnd),
            )
        }
        verify(exactly = 1) {
            spendingAnalysisRepository.saveAnalysis(
                match {
                    it.accountId == account.accountId &&
                        it.analysisMonth == analysisMonth &&
                        it.totalAmount == 15000L &&
                        it.transactionCount == 2L &&
                        it.trend.name == "INCREASING"
                },
            )
        }
        assertThat(analyzedCount).isEqualTo(1)
    }

    @Test
    fun `when an account was already analyzed this month then skips it`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (listOf(account) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 1, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 1L)
        every { spendingAnalysisRepository.hasAnalysis(account.accountId, analysisMonth) } returns true

        val analyzedCount = service.analyzeMonthlySpending(analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd)

        verify(exactly = 0) { accountRepository.summarizeTransactions(any()) }
        verify(exactly = 0) { spendingAnalysisRepository.saveAnalysis(any()) }
        assertThat(analyzedCount).isEqualTo(0)
    }

    @Test
    fun `when there are no active accounts then returns 0 without summarizing`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 100, status = listOf(AccountStatus.ACTIVE.name)))
        } returns (emptyList<Account>() to 0L)

        val analyzedCount = service.analyzeMonthlySpending(analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd)

        assertThat(analyzedCount).isEqualTo(0)
        verify(exactly = 0) { spendingAnalysisRepository.saveAnalysis(any()) }
    }
}
