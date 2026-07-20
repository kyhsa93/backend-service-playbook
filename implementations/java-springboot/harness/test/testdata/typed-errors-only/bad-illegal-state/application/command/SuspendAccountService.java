package com.example.accountservice.account.application.command;

public class SuspendAccountService {

    public void suspend(String accountId) {
        if (accountId == null) {
            throw new IllegalStateException("accountId가 없습니다.");
        }
    }
}
