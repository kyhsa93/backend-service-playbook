package com.example.accountservice.payment.application.command;

import com.example.accountservice.account.domain.AccountRepository;

public class CreatePaymentService {
    private final AccountRepository accountRepository;

    public CreatePaymentService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }
}
