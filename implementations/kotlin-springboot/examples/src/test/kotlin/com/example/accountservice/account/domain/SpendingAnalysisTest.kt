package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpendingAnalysisTest {
    @Test
    fun `create when spending increased by more than 10 percent then trend is INCREASING`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 15000,
                transactionCount = 3,
                previousTotalAmount = 10000,
            )

        assertThat(analysis.changeFromPreviousMonth).isEqualTo(50)
        assertThat(analysis.trend).isEqualTo(SpendingTrend.INCREASING)
        assertThat(analysis.averageAmount).isEqualTo(5000)
    }

    @Test
    fun `create when spending decreased by more than 10 percent then trend is DECREASING`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 5000,
                transactionCount = 1,
                previousTotalAmount = 10000,
            )

        assertThat(analysis.changeFromPreviousMonth).isEqualTo(-50)
        assertThat(analysis.trend).isEqualTo(SpendingTrend.DECREASING)
    }

    @Test
    fun `create when the change is within 10 percent then trend is STABLE`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 10500,
                transactionCount = 2,
                previousTotalAmount = 10000,
            )

        assertThat(analysis.changeFromPreviousMonth).isEqualTo(5)
        assertThat(analysis.trend).isEqualTo(SpendingTrend.STABLE)
    }

    @Test
    fun `create when there was no spending in either month then 0 percent change and STABLE`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 0,
                transactionCount = 0,
                previousTotalAmount = 0,
            )

        assertThat(analysis.changeFromPreviousMonth).isEqualTo(0)
        assertThat(analysis.trend).isEqualTo(SpendingTrend.STABLE)
        assertThat(analysis.averageAmount).isEqualTo(0)
    }

    @Test
    fun `create when there was no spending last month but spending this month then 100 percent change and INCREASING`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 3000,
                transactionCount = 1,
                previousTotalAmount = 0,
            )

        assertThat(analysis.changeFromPreviousMonth).isEqualTo(100)
        assertThat(analysis.trend).isEqualTo(SpendingTrend.INCREASING)
    }

    @Test
    fun `create when transactionCount is 0 then averageAmount is 0 rather than dividing by zero`() {
        val analysis =
            SpendingAnalysis.create(
                accountId = "account-1",
                analysisMonth = "2026-07",
                totalAmount = 0,
                transactionCount = 0,
                previousTotalAmount = 5000,
            )

        assertThat(analysis.averageAmount).isEqualTo(0)
    }
}
