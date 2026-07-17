package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record MoneyWithdrawnEvent(
        String accountId,
        String email,
        String transactionId,
        Money amount,
        Money balanceAfter,
        LocalDateTime createdAt) {}
