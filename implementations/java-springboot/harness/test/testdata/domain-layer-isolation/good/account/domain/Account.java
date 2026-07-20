package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public class Account {
    private String accountId;
    private LocalDateTime createdAt;

    private Account() {
    }

    public static Account create(String ownerId) {
        return new Account();
    }
}
