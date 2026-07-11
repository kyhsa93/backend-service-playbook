package com.example.accountservice.account.application.query;

import org.springframework.stereotype.Service;

@Service
public class GetAccountService {

    private final AccountQuery accountQuery;

    public GetAccountService(AccountQuery accountQuery) {
        this.accountQuery = accountQuery;
    }
}
