package com.example.accountservice.account.domain

sealed class AccountException(message: String) : RuntimeException(message)

class AccountNotFoundException(accountId: String) : AccountException("account not found: $accountId")
class InvalidAmountException : AccountException("금액은 0보다 커야 합니다.")
class InvalidMoneyAmountException : AccountException("금액은 0 이상이어야 합니다.")
class CurrencyMismatchException : AccountException("통화가 일치하지 않습니다.")
class DepositRequiresActiveAccountException : AccountException("활성 상태의 계좌만 입금할 수 있습니다.")
class WithdrawRequiresActiveAccountException : AccountException("활성 상태의 계좌만 출금할 수 있습니다.")
class InsufficientBalanceException : AccountException("잔액이 부족합니다.")
class SuspendRequiresActiveAccountException : AccountException("활성 상태의 계좌만 정지할 수 있습니다.")
class ReactivateRequiresSuspendedAccountException : AccountException("정지 상태의 계좌만 재개할 수 있습니다.")
class AccountAlreadyClosedException : AccountException("이미 종료된 계좌입니다.")
class AccountBalanceNotZeroException : AccountException("잔액이 0이 아닌 계좌는 종료할 수 없습니다.")
