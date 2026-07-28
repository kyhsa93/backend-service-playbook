package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.application.query.SpendingForecastFindQuery
import com.example.accountservice.account.application.query.SpendingForecastQuery
import com.example.accountservice.account.domain.SpendingForecast
import com.example.accountservice.account.domain.SpendingForecastRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class SpendingForecastRepositoryImpl(
    private val jpaRepository: SpendingForecastJpaRepository,
) : SpendingForecastRepository,
    SpendingForecastQuery {
    @Transactional
    override fun saveForecast(forecast: SpendingForecast) {
        jpaRepository.save(SpendingForecastMapper.toNewEntity(forecast))
    }

    override fun hasForecast(
        accountId: String,
        forecastMonth: String,
    ): Boolean = jpaRepository.existsByAccountIdAndForecastMonth(accountId, forecastMonth)

    override fun findForecasts(query: SpendingForecastFindQuery): List<SpendingForecast> =
        jpaRepository
            .findByAccountIdAndForecastMonth(query.accountId, query.forecastMonth)
            ?.let { listOf(SpendingForecastMapper.toDomain(it)) }
            ?: emptyList()
}
