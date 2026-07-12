package com.example.accountservice.account.domain;

import java.util.List;

/** {@code findTransactions} 조회 결과 — 목록과 총 개수를 함께 반환한다(repository-pattern.md 참고). */
public record TransactionsWithCount(List<Transaction> transactions, long count) {}
