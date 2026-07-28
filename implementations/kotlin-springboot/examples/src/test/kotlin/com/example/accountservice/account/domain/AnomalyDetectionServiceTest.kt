package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// AnomalyDetectionService is a Domain Service that flags a withdrawal as a statistical outlier
// (z-score > 3) against the account's own trailing withdrawal history — since it has no framework
// annotations, it is instantiated directly without a Spring context to verify only the decision
// logic (the same style as TransferEligibilityServiceTest).
class AnomalyDetectionServiceTest {
    private val service = AnomalyDetectionService()

    @Test
    fun `is not anomalous when the history has fewer than 5 data points`() {
        val history = listOf(10000L, 11000L, 9000L)

        val result = service.isAnomalous(history, 500_000L)

        assertThat(result).isFalse()
    }

    @Test
    fun `is anomalous when the amount is far outside the history's normal range`() {
        val history = listOf(10000L, 12000L, 9000L, 11000L, 10500L)

        val result = service.isAnomalous(history, 5_000_000L)

        assertThat(result).isTrue()
    }

    @Test
    fun `is not anomalous when the amount is within the history's normal range`() {
        val history = listOf(10000L, 12000L, 9000L, 11000L, 10500L)

        val result = service.isAnomalous(history, 10800L)

        assertThat(result).isFalse()
    }

    @Test
    fun `is anomalous when the history is perfectly uniform and the amount differs from it`() {
        val history = listOf(10000L, 10000L, 10000L, 10000L, 10000L)

        val result = service.isAnomalous(history, 10001L)

        assertThat(result).isTrue()
    }

    @Test
    fun `is not anomalous when the history is perfectly uniform and the amount matches it exactly`() {
        val history = listOf(10000L, 10000L, 10000L, 10000L, 10000L)

        val result = service.isAnomalous(history, 10000L)

        assertThat(result).isFalse()
    }
}
