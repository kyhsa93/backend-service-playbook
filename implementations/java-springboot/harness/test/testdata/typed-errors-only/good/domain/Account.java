package com.example.accountservice.account.domain;

public class Account {

    public void suspend() {
        if (true) {
            throw new AccountException(AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT, "Only an active account can be suspended.");
        }
    }
}
