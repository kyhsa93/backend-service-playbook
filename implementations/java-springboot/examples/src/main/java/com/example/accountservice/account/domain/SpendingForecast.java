package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * A materialized read-model row — the ETL's precomputed answer to "what will this account likely
 * spend next month," produced monthly by {@code ForecastSpendingService} (which trains a fresh
 * {@code SpendingForecastModel} from the account's own spending_analysis history on every run) and
 * served as-is by {@code GetSpendingForecastService}. No business invariant lives here — the one
 * real transform step (fitting the model) already happened before this object is constructed — so
 * this stays a plain data holder, the same reasoning as {@link SpendingAnalysis}.
 */
public class SpendingForecast {

    private String forecastId;
    private String accountId;
    private String forecastMonth;
    private long predictedAmount;
    private ForecastConfidence confidence;
    private int historyMonthsUsed;
    private LocalDateTime createdAt;

    private SpendingForecast() {}

    public static SpendingForecast create(
            String accountId,
            String forecastMonth,
            long predictedAmount,
            ForecastConfidence confidence,
            int historyMonthsUsed) {
        SpendingForecast forecast = new SpendingForecast();
        forecast.forecastId = IdGenerator.generate();
        forecast.accountId = accountId;
        forecast.forecastMonth = forecastMonth;
        forecast.predictedAmount = predictedAmount;
        forecast.confidence = confidence;
        forecast.historyMonthsUsed = historyMonthsUsed;
        forecast.createdAt = LocalDateTime.now();
        return forecast;
    }

    /**
     * Used by a Repository implementation to reconstitute a SpendingForecast from persisted data (a
     * JPA entity, etc.).
     */
    public static SpendingForecast reconstitute(
            String forecastId,
            String accountId,
            String forecastMonth,
            long predictedAmount,
            ForecastConfidence confidence,
            int historyMonthsUsed,
            LocalDateTime createdAt) {
        SpendingForecast forecast = new SpendingForecast();
        forecast.forecastId = forecastId;
        forecast.accountId = accountId;
        forecast.forecastMonth = forecastMonth;
        forecast.predictedAmount = predictedAmount;
        forecast.confidence = confidence;
        forecast.historyMonthsUsed = historyMonthsUsed;
        forecast.createdAt = createdAt;
        return forecast;
    }

    public String getForecastId() {
        return forecastId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getForecastMonth() {
        return forecastMonth;
    }

    public long getPredictedAmount() {
        return predictedAmount;
    }

    public ForecastConfidence getConfidence() {
        return confidence;
    }

    public int getHistoryMonthsUsed() {
        return historyMonthsUsed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
