package com.example.accountservice.account.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The query parameters for {@link AccountRepository#summarizeTransactions} — narrows by
 * accountId/type and a half-open {@code [createdAtFrom, createdAtTo)} date range.
 */
public record TransactionSummaryQuery(
        String accountId,
        List<TransactionType> type,
        LocalDateTime createdAtFrom,
        LocalDateTime createdAtTo) {}
