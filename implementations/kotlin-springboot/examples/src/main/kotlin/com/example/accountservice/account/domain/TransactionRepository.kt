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
}
