package com.example.accountservice.account.application.query;

import java.time.LocalDateTime;

public record GetAccountResult(
        String accountId,
        String ownerId,
        String email,
        MoneyResult balance,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public record MoneyResult(long amount, String currency) {}
}
