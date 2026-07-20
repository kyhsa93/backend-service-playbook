package com.example.accountservice.account.domain;

public class Account {
    private String accountId;

    private Account() {
    }

    public static Account create(String ownerId) {
        return new Account();
    }
}
