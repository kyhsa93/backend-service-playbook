package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.ForecastConfidence
import com.example.accountservice.account.domain.SpendingForecast

/**
 * Converts between SpendingForecast (pure domain) and SpendingForecastJpaEntity (JPA mapping).
 * Used only inside SpendingForecastRepositoryImpl. There is no `updateEntity` (unlike
 * AccountMapper) — a SpendingForecast row is write-once, the same reasoning SpendingAnalysisMapper
 * has no update method.
 */
internal object SpendingForecastMapper {
    fun toDomain(entity: SpendingForecastJpaEntity): SpendingForecast =
        SpendingForecast.reconstitute(
            forecastId = entity.forecastId,
            accountId = entity.accountId,
            forecastMonth = entity.forecastMonth,
            predictedAmount = entity.predictedAmount,
            confidence = ForecastConfidence.valueOf(entity.confidence),
            historyMonthsUsed = entity.historyMonthsUsed,
            createdAt = entity.createdAt,
        )

    fun toNewEntity(forecast: SpendingForecast): SpendingForecastJpaEntity =
        SpendingForecastJpaEntity(
            id = null,
            forecastId = forecast.forecastId,
            accountId = forecast.accountId,
            forecastMonth = forecast.forecastMonth,
            predictedAmount = forecast.predictedAmount,
            confidence = forecast.confidence.name,
            historyMonthsUsed = forecast.historyMonthsUsed,
            createdAt = forecast.createdAt,
        )
}
