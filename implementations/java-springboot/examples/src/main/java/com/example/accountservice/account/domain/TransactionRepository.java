package com.example.accountservice.account.domain;

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
}
