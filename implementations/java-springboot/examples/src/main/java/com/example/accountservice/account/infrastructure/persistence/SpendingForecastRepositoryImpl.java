package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.application.query.SpendingForecastQuery;
import com.example.accountservice.account.domain.SpendingForecast;
import com.example.accountservice.account.domain.SpendingForecastRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements both the write-side {@link SpendingForecastRepository} and the read-side {@link
 * SpendingForecastQuery} for SpendingForecast in a single class — the same structure as {@code
 * SpendingAnalysisRepositoryImpl}. Each Application layer class only injects the narrow interface
 * it needs (Repository or Query).
 */
@Repository
@RequiredArgsConstructor
public class SpendingForecastRepositoryImpl
        implements SpendingForecastRepository, SpendingForecastQuery {

    private final SpendingForecastJpaRepository jpaRepository;

    @Override
    @Transactional
    public void saveForecast(SpendingForecast forecast) {
        jpaRepository.save(SpendingForecastMapper.toNewEntity(forecast));
    }

    @Override
    public boolean hasForecast(String accountId, String forecastMonth) {
        return jpaRepository.existsByAccountIdAndForecastMonth(accountId, forecastMonth);
    }

    @Override
    public Optional<SpendingForecast> findForecast(String accountId, String forecastMonth) {
        return jpaRepository
                .findByAccountIdAndForecastMonth(accountId, forecastMonth)
                .map(SpendingForecastMapper::toDomain);
    }
}
