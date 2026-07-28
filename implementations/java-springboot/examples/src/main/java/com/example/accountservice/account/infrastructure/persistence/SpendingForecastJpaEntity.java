package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.ForecastConfidence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * The JPA-mapping counterpart of account/domain/SpendingForecast.java. The Domain object
 * (SpendingForecast) has no knowledge of this class at all — the conversion is handled entirely by
 * SpendingForecastMapper. One row per (accountId, forecastMonth) — the unique constraint is the
 * idempotency backstop, the same role as spending_analysis's uniqueness check. Insert-only: a row
 * is never updated or soft-deleted after it's written, so there is no updatedAt/deletedAt column.
 */
@Entity
@Table(
        name = "spending_forecast",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_spending_forecast_account_month",
                        columnNames = {"accountId", "forecastMonth"}))
public class SpendingForecastJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String forecastId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String forecastMonth;

    @Column(nullable = false)
    private long predictedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ForecastConfidence confidence;

    @Column(nullable = false)
    private int historyMonthsUsed;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected SpendingForecastJpaEntity() {}

    SpendingForecastJpaEntity(
            Long id,
            String forecastId,
            String accountId,
            String forecastMonth,
            long predictedAmount,
            ForecastConfidence confidence,
            int historyMonthsUsed,
            LocalDateTime createdAt) {
        this.id = id;
        this.forecastId = forecastId;
        this.accountId = accountId;
        this.forecastMonth = forecastMonth;
        this.predictedAmount = predictedAmount;
        this.confidence = confidence;
        this.historyMonthsUsed = historyMonthsUsed;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getForecastId() {
        return forecastId;
    }

    String getAccountId() {
        return accountId;
    }

    String getForecastMonth() {
        return forecastMonth;
    }

    long getPredictedAmount() {
        return predictedAmount;
    }

    ForecastConfidence getConfidence() {
        return confidence;
    }

    int getHistoryMonthsUsed() {
        return historyMonthsUsed;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
