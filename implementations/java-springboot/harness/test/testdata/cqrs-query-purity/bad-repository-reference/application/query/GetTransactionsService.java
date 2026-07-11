package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountRepository;
import org.springframework.stereotype.Service;

@Service
public class GetTransactionsService {

    private final AccountRepository accountRepository;

    public GetTransactionsService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }
}
