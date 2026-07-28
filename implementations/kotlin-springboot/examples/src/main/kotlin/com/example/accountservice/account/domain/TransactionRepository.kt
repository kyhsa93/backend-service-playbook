package com.example.accountservice.account.domain

/**
 * Separate from [AccountRepository] — that one only ever inserts Transaction rows in bulk as a side
 * effect of saveAccount (Transaction rows are otherwise insert-only there). This is the
 * find→modify-via-domain-method→save<Noun> cycle CategorizeTransactionEventHandler needs for the one
 * field (category) that legitimately gets set after the fact (see repository-pattern.md's "a
 * Repository must not have an update method" rule).
 */
interface TransactionRepository {
    fun findTransaction(transactionId: String): Transaction?

    fun saveTransaction(transaction: Transaction)

    /**
     * The training data for [AnomalyDetectionService] — the account's own recent WITHDRAWAL
     * amounts (order doesn't matter here, unlike `SpendingAnalysisRepository.findRecentAnalyses`,
     * since the Domain Service only computes a mean/standard deviation over the set).
     * [excludeTransactionId] is the withdrawal being judged itself — by the time
     * `DetectWithdrawalAnomalyEventHandler` runs (after the Outbox has delivered
     * MoneyWithdrawnEvent), that transaction is already persisted, so it must be excluded or it
     * would skew its own baseline.
     */
    fun findRecentWithdrawalAmounts(
        accountId: String,
        excludeTransactionId: String,
        limit: Int,
    ): List<Long>
}
