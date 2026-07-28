package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record MoneyWithdrawnEvent(
        String accountId,
        String email,
        String transactionId,
        Money amount,
        Money balanceAfter,
        // Carried through so CategorizeTransactionEventHandler doesn't need a separate lookup to
        // react — the same reasoning as every other field on this event. Absent when the requester
        // didn't attach one; CategorizeTransactionEventHandler skips categorization entirely in
        // that case.
        String merchantName,
        LocalDateTime createdAt) {}
