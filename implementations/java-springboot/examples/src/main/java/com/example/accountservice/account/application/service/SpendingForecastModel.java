package com.example.accountservice.account.application.service;

import com.example.accountservice.account.domain.ForecastConfidence;
import java.util.List;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) — the core of this feature is
 * a statistical model, an implementation concern independent of any domain rule, so it's abstracted
 * the same way {@code NlTransactionQueryTranslator} abstracts an LLM call. The interface takes/
 * returns plain data only, never a JPA entity or an account/domain Aggregate type, so the
 * implementation (currently an in-process regression) could later be swapped for a call to an
 * external ML service without touching any caller.
 */
public interface SpendingForecastModel {

    /**
     * One month's worth of the training signal — reuses the spending_analysis read-model the
     * account.analyze-monthly-spending ETL already produces, rather than re-aggregating raw
     * Transaction rows.
     */
    record HistoryPoint(String analysisMonth, long totalAmount) {}

    record Prediction(long predictedAmount, ForecastConfidence confidence) {}

    /**
     * {@code history} must be in chronological (oldest-first) order. Callers are expected to
     * enforce a minimum history length before calling this — see {@code
     * MIN_HISTORY_MONTHS_FOR_FORECAST} in {@code ForecastSpendingService}.
     */
    Prediction predict(List<HistoryPoint> history);
}
