package com.example.accountservice.account.domain

sealed class AccountException(
    message: String,
    val code: AccountErrorCode,
) : RuntimeException(message)

class AccountNotFoundException(
    accountId: String,
) : AccountException("account not found: $accountId", AccountErrorCode.ACCOUNT_NOT_FOUND)

class InvalidAmountException : AccountException("금액은 0보다 커야 합니다.", AccountErrorCode.INVALID_AMOUNT)

class InvalidMoneyAmountException : AccountException("금액은 0 이상이어야 합니다.", AccountErrorCode.INVALID_MONEY_AMOUNT)

class CurrencyMismatchException : AccountException("통화가 일치하지 않습니다.", AccountErrorCode.CURRENCY_MISMATCH)

class DepositRequiresActiveAccountException :
    AccountException("활성 상태의 계좌만 입금할 수 있습니다.", AccountErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT)

class WithdrawRequiresActiveAccountException :
    AccountException("활성 상태의 계좌만 출금할 수 있습니다.", AccountErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT)

class InsufficientBalanceException : AccountException("잔액이 부족합니다.", AccountErrorCode.INSUFFICIENT_BALANCE)

class SuspendRequiresActiveAccountException :
    AccountException("활성 상태의 계좌만 정지할 수 있습니다.", AccountErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT)

class ReactivateRequiresSuspendedAccountException :
    AccountException("정지 상태의 계좌만 재개할 수 있습니다.", AccountErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT)

class AccountAlreadyClosedException : AccountException("이미 종료된 계좌입니다.", AccountErrorCode.ACCOUNT_ALREADY_CLOSED)

class AccountBalanceNotZeroException : AccountException("잔액이 0이 아닌 계좌는 종료할 수 없습니다.", AccountErrorCode.ACCOUNT_BALANCE_NOT_ZERO)

class DeleteRequiresClosedAccountException : AccountException("종료 상태의 계좌만 삭제할 수 있습니다.", AccountErrorCode.DELETE_REQUIRES_CLOSED_ACCOUNT)
