package com.example.accountservice.account.domain;

import java.util.List;

/**
 * The result of a {@code findTransactions} lookup — returns the list together with the total count
 * (see repository-pattern.md).
 */
public record TransactionsWithCount(List<Transaction> transactions, long count) {}
