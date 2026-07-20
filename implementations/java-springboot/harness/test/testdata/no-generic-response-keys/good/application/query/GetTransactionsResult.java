package com.example.accountservice.account.application.query;

import java.util.List;

public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(String transactionId, String type) {}
}
