package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.account.application.command.CreateAccountService;

public class AccountController {
    private final CreateAccountService createAccountService;

    public AccountController(CreateAccountService createAccountService) {
        this.createAccountService = createAccountService;
    }
}
