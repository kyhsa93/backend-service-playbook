package com.example.accountservice.account.application.service;

import com.example.accountservice.account.application.query.GetTransactionsResult.TransactionSummary;
import java.util.List;

/**
 * A Technical Service (see root docs/architecture/domain-service.md) generating a natural-language
 * answer grounded in already-retrieved transaction records — the "Generate" step of a
 * structured-data RAG pipeline. It never queries data itself; {@code AskTransactionHistoryService}
 * retrieves the records first (scoped to the authenticated requester) and passes them in here as
 * plain data, so this service can never widen what's visible beyond what was already fetched.
 */
public interface NlTransactionAnswerComposer {

    String compose(String question, List<TransactionSummary> transactions);
}
