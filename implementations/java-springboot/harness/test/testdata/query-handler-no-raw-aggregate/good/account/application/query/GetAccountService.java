package com.example.accountservice.account.application.query;

public class GetAccountService {
    public GetAccountResult getAccount(String accountId, String requesterId) {
        return new GetAccountResult(accountId, requesterId);
    }
}
