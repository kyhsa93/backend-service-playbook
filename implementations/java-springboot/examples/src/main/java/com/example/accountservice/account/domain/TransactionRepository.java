package com.example.accountservice.account.domain;

import java.util.List;

/**
 * Separate from {@link AccountRepository} — that one only ever inserts Transaction rows in bulk as
 * a side effect of {@code saveAccount} (Transaction rows are otherwise insert-only there). This is
 * the find→modify-via-domain-method→save&lt;Noun&gt; cycle {@code
 * CategorizeTransactionEventHandler} needs for the one field ({@code category}) that legitimately
 * gets set after the fact (see repository-pattern.md's "a Repository must not have an update
 * method" rule).
 */
public interface TransactionRepository {

    Transaction findTransaction(String transactionId);

    void saveTransaction(Transaction transaction);

    /**
     * The training data for {@link AnomalyDetectionService} — the account's own recent WITHDRAWAL
     * amounts (order doesn't matter here, unlike {@code
     * SpendingAnalysisRepository#findRecentAnalyses}, since the Domain Service only computes a
     * mean/standard deviation over the set). {@code excludeTransactionId} is the withdrawal being
     * judged itself — by the time {@code DetectWithdrawalAnomalyEventHandler} runs (after the
     * Outbox has delivered MoneyWithdrawn), that transaction is already persisted, so it must be
     * excluded or it would skew its own baseline.
     */
    List<Long> findRecentWithdrawalAmounts(
            String accountId, String excludeTransactionId, int limit);
}
