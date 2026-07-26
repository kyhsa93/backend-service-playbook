package com.example.accountservice.account.domain;

import java.time.LocalDate;

/**
 * The query parameters for {@code findTransactions} — {@code type}/{@code fromDate}/{@code toDate}
 * are optional narrowing filters (see {@code AskTransactionHistoryService} and the {@code GET
 * /accounts/{accountId}/transactions} endpoint). Deliberately has no {@code ownerId} field: account
 * ownership is always verified separately via {@code AccountQuery.findAccounts} before this query
 * ever runs, scoped only by {@code accountId} (see repository-pattern.md).
 */
public record TransactionFindQuery(
        String accountId,
        int page,
        int take,
        TransactionType type,
        LocalDate fromDate,
        LocalDate toDate) {

    public TransactionFindQuery(String accountId, int page, int take) {
        this(accountId, page, take, null, null, null);
    }
}
