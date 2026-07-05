package com.example.accountservice.account.application.command;

import java.time.LocalDateTime;

public record CreateAccountResult(
        String accountId,
        String ownerId,
        String email,
        MoneyResult balance,
        String status,
        LocalDateTime createdAt
) {
    public record MoneyResult(long amount, String currency) {}
}
