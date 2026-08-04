package com.example.accountservice.account.domain;

import com.example.accountservice.common.UtcClock;
import java.time.LocalDateTime;

public class Account {
    private LocalDateTime createdAt;

    private Account() {
    }

    public static Account create(String ownerId) {
        Account account = new Account();
        account.createdAt = UtcClock.now();
        return account;
    }
}
