package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record MoneyDepositedEvent(
        String accountId,
        String transactionId,
        Money amount,
        Money balanceAfter,
        LocalDateTime createdAt
) {}
