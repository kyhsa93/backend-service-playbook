package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl;

public class AccountController {
    private final AccountRepositoryImpl accountRepositoryImpl;

    public AccountController(AccountRepositoryImpl accountRepositoryImpl) {
        this.accountRepositoryImpl = accountRepositoryImpl;
    }
}
