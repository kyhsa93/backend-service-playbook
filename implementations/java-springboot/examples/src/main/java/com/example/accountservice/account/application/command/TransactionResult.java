package com.example.accountservice.account.application.command;

import java.time.LocalDateTime;

public record TransactionResult(
        String transactionId,
        String accountId,
        String type,
        MoneyResult amount,
        LocalDateTime createdAt
) {
    public record MoneyResult(long amount, String currency) {}
}
