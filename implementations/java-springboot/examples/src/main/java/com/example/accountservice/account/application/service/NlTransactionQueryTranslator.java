package com.example.accountservice.account.application.service;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) translating a free-text
 * question about an account's transaction history into a structured filter. This is the
 * "Retrieve"-preparation step of a structured-data RAG pipeline: {@link
 * NlTransactionAnswerComposer} (the "Generate" step) is the other half, and {@code
 * AccountQuery.findTransactions} itself is the "Retrieve" step in between.
 */
public interface NlTransactionQueryTranslator {

    TransactionFilter translate(String question);
}
