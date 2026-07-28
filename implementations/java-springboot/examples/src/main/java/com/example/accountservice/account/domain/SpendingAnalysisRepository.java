package com.example.accountservice.account.domain;

import java.util.List;

public interface SpendingAnalysisRepository {

    void saveAnalysis(SpendingAnalysis analysis);

    /**
     * A cheap idempotency check ahead of the real work — the (accountId, analysisMonth) unique
     * constraint on the table is the last line of defense, the same two-layer pattern as {@code
     * Card#shouldSendStatement}/the card_sent_email uniqueness check.
     */
    boolean hasAnalysis(String accountId, String analysisMonth);

    /**
     * The training data for {@code ForecastSpendingService} — every analysis row strictly before
     * {@code beforeMonth}, capped at {@code limit}, returned oldest-first (chronological order)
     * since {@code SpendingForecastModel#predict} treats list position as the month index.
     */
    List<SpendingAnalysis> findRecentAnalyses(String accountId, String beforeMonth, int limit);
}
