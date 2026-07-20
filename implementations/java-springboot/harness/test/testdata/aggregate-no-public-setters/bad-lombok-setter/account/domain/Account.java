package com.example.accountservice.account.domain;

import lombok.Setter;

@Setter
public class Account {
    private String accountId;
    private AccountStatus status;

    private Account() {
    }

    public static Account create(String ownerId) {
        return new Account();
    }
}
