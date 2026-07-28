package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.SpendingForecast;

/**
 * The class dedicated to converting between SpendingForecast (pure domain) and
 * SpendingForecastJpaEntity (JPA mapping). It is used only inside SpendingForecastRepositoryImpl.
 * Since a SpendingForecast is immutable after creation, only insert-side conversion is needed.
 */
final class SpendingForecastMapper {

    private SpendingForecastMapper() {}

    static SpendingForecast toDomain(SpendingForecastJpaEntity entity) {
        return SpendingForecast.reconstitute(
                entity.getForecastId(),
                entity.getAccountId(),
                entity.getForecastMonth(),
                entity.getPredictedAmount(),
                entity.getConfidence(),
                entity.getHistoryMonthsUsed(),
                entity.getCreatedAt());
    }

    static SpendingForecastJpaEntity toNewEntity(SpendingForecast forecast) {
        return new SpendingForecastJpaEntity(
                null,
                forecast.getForecastId(),
                forecast.getAccountId(),
                forecast.getForecastMonth(),
                forecast.getPredictedAmount(),
                forecast.getConfidence(),
                forecast.getHistoryMonthsUsed(),
                forecast.getCreatedAt());
    }
}
