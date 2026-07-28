package com.example.accountservice.account.infrastructure

import com.example.accountservice.account.application.service.SpendingHistoryPoint
import com.example.accountservice.account.domain.ForecastConfidence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpendingForecastModelImplTest {
    private val model = SpendingForecastModelImpl()

    @Test
    fun `predict when history is a perfect linear trend then extrapolates exactly with high confidence`() {
        val prediction =
            model.predict(
                listOf(
                    SpendingHistoryPoint("2026-04", 10000),
                    SpendingHistoryPoint("2026-05", 20000),
                    SpendingHistoryPoint("2026-06", 30000),
                ),
            )

        assertThat(prediction.predictedAmount).isEqualTo(40000)
        assertThat(prediction.confidence).isEqualTo(ForecastConfidence.HIGH)
    }

    @Test
    fun `predict when history is perfectly flat then predicts the same amount with high confidence`() {
        val prediction =
            model.predict(
                listOf(
                    SpendingHistoryPoint("2026-04", 15000),
                    SpendingHistoryPoint("2026-05", 15000),
                    SpendingHistoryPoint("2026-06", 15000),
                ),
            )

        assertThat(prediction.predictedAmount).isEqualTo(15000)
        assertThat(prediction.confidence).isEqualTo(ForecastConfidence.HIGH)
    }

    @Test
    fun `predict when history is noisy and non-linear then reports lower confidence`() {
        val prediction =
            model.predict(
                listOf(
                    SpendingHistoryPoint("2026-01", 5000),
                    SpendingHistoryPoint("2026-02", 40000),
                    SpendingHistoryPoint("2026-03", 3000),
                    SpendingHistoryPoint("2026-04", 35000),
                    SpendingHistoryPoint("2026-05", 4000),
                    SpendingHistoryPoint("2026-06", 38000),
                ),
            )

        assertThat(prediction.confidence).isNotEqualTo(ForecastConfidence.HIGH)
    }

    @Test
    fun `predict when the trend is sharply decreasing then floors the prediction at 0 instead of going negative`() {
        val prediction =
            model.predict(
                listOf(
                    SpendingHistoryPoint("2026-04", 30000),
                    SpendingHistoryPoint("2026-05", 15000),
                    SpendingHistoryPoint("2026-06", 1000),
                ),
            )

        assertThat(prediction.predictedAmount).isEqualTo(0)
    }
}
