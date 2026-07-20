package com.example.accountservice.account.domain;

import com.example.accountservice.account.application.command.CreateAccountCommand;

public class Account {
    private String accountId;

    private Account() {
    }

    public static Account create(CreateAccountCommand command) {
        return new Account();
    }
}
