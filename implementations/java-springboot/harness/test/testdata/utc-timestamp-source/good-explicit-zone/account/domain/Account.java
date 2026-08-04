package com.example.accountservice.account.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class Account {
    private LocalDateTime createdAt;

    private Account() {
    }

    // An explicit zone argument is unambiguous, so it is not a bare reading.
    public static Account create(String ownerId) {
        Account account = new Account();
        account.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        return account;
    }
}
