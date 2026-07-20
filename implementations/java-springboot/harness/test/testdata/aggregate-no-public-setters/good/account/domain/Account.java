package com.example.accountservice.account.domain;

public class Account {
    private String accountId;
    private AccountStatus status;

    private Account() {
    }

    public static Account create(String ownerId) {
        return new Account();
    }

    public void suspend() {
        this.status = AccountStatus.SUSPENDED;
    }

    public String getAccountId() {
        return accountId;
    }
}
