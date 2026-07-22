package com.example.accountservice.account.domain

sealed class AccountException(
    message: String,
    val code: AccountErrorCode,
) : RuntimeException(message)

class AccountNotFoundException(
    accountId: String,
) : AccountException("account not found: $accountId", AccountErrorCode.ACCOUNT_NOT_FOUND)

class InvalidAmountException : AccountException("The amount must be greater than 0.", AccountErrorCode.INVALID_AMOUNT)

class InvalidMoneyAmountException : AccountException("The amount must be 0 or greater.", AccountErrorCode.INVALID_MONEY_AMOUNT)

class CurrencyMismatchException : AccountException("The currencies do not match.", AccountErrorCode.CURRENCY_MISMATCH)

class DepositRequiresActiveAccountException :
    AccountException("Only an active account can receive a deposit.", AccountErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT)

class WithdrawRequiresActiveAccountException :
    AccountException("Only an active account can make a withdrawal.", AccountErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT)

class InsufficientBalanceException : AccountException("Insufficient balance.", AccountErrorCode.INSUFFICIENT_BALANCE)

class SuspendRequiresActiveAccountException :
    AccountException("Only an active account can be suspended.", AccountErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT)

class ReactivateRequiresSuspendedAccountException :
    AccountException("Only a suspended account can be reactivated.", AccountErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT)

class AccountAlreadyClosedException : AccountException("This account is already closed.", AccountErrorCode.ACCOUNT_ALREADY_CLOSED)

class AccountBalanceNotZeroException :
    AccountException("An account with a non-zero balance cannot be closed.", AccountErrorCode.ACCOUNT_BALANCE_NOT_ZERO)

class DeleteRequiresClosedAccountException :
    AccountException("Only a closed account can be deleted.", AccountErrorCode.DELETE_REQUIRES_CLOSED_ACCOUNT)

class TransferSameAccountException :
    AccountException("The withdrawal account and deposit account cannot be the same.", AccountErrorCode.TRANSFER_SAME_ACCOUNT)
