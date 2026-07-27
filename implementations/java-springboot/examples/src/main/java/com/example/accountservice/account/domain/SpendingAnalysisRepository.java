package com.example.accountservice.account.domain;

public interface SpendingAnalysisRepository {

    void saveAnalysis(SpendingAnalysis analysis);

    /**
     * A cheap idempotency check ahead of the real work — the (accountId, analysisMonth) unique
     * constraint on the table is the last line of defense, the same two-layer pattern as {@code
     * Card#shouldSendStatement}/the card_sent_email uniqueness check.
     */
    boolean hasAnalysis(String accountId, String analysisMonth);
}
