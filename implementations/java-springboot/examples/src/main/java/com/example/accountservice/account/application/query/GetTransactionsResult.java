package com.example.accountservice.account.application.query;

import java.time.LocalDateTime;
import java.util.List;

public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(String transactionId, String type, MoneyResult amount, LocalDateTime createdAt) {}
    public record MoneyResult(long amount, String currency) {}
}
