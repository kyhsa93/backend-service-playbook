package com.example.accountservice.account.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpendingForecastJpaRepository
        extends JpaRepository<SpendingForecastJpaEntity, Long> {

    boolean existsByAccountIdAndForecastMonth(String accountId, String forecastMonth);

    Optional<SpendingForecastJpaEntity> findByAccountIdAndForecastMonth(
            String accountId, String forecastMonth);
}
