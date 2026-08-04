package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public class Account {
    private LocalDateTime createdAt;

    private Account() {
    }

    public static Account create(String ownerId) {
        Account account = new Account();
        account.createdAt = LocalDateTime.now();
        return account;
    }
}
