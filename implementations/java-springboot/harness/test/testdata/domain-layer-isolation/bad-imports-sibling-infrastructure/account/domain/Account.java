package com.example.accountservice.account.domain;

import com.example.accountservice.card.infrastructure.CardRepositoryImpl;

public class Account {
    private String accountId;

    private Account() {
    }

    public static Account create(CardRepositoryImpl repository) {
        return new Account();
    }
}
