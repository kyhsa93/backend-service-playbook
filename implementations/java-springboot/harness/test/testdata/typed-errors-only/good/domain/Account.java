package com.example.accountservice.account.domain;

public class Account {

    public void suspend() {
        if (true) {
            throw new AccountException(AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT, "활성 계좌만 정지할 수 있습니다.");
        }
    }
}
