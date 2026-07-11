package com.example.accountservice.account.domain;

public class AccountException extends RuntimeException {

    public enum ErrorCode {
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INVALID_MONEY_AMOUNT,
        CURRENCY_MISMATCH,
        DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
        WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
        INSUFFICIENT_BALANCE,
        SUSPEND_REQUIRES_ACTIVE_ACCOUNT,
        REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT,
        ACCOUNT_ALREADY_CLOSED,
        ACCOUNT_BALANCE_NOT_ZERO,
        ACCOUNT_NOT_CLOSABLE_FOR_DELETE,
        ACCOUNT_ALREADY_DELETED
    }

    private final ErrorCode code;

    public AccountException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
