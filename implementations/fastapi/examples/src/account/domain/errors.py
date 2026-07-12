from .error_codes import AccountErrorCode


class AccountError(Exception):
    code: AccountErrorCode


class AccountNotFoundError(AccountError):
    code = AccountErrorCode.ACCOUNT_NOT_FOUND

    def __init__(self, account_id: str) -> None:
        super().__init__(f"account not found: {account_id}")
        self.account_id = account_id


class InvalidAmountError(AccountError):
    code = AccountErrorCode.INVALID_AMOUNT

    def __init__(self) -> None:
        super().__init__("금액은 0보다 커야 합니다.")


class InvalidMoneyAmountError(AccountError):
    code = AccountErrorCode.INVALID_MONEY_AMOUNT

    def __init__(self) -> None:
        super().__init__("금액은 0 이상이어야 합니다.")


class CurrencyMismatchError(AccountError):
    code = AccountErrorCode.CURRENCY_MISMATCH

    def __init__(self) -> None:
        super().__init__("통화가 일치하지 않습니다.")


class DepositRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 입금할 수 있습니다.")


class WithdrawRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 출금할 수 있습니다.")


class InsufficientBalanceError(AccountError):
    code = AccountErrorCode.INSUFFICIENT_BALANCE

    def __init__(self) -> None:
        super().__init__("잔액이 부족합니다.")


class SuspendRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 정지할 수 있습니다.")


class ReactivateRequiresSuspendedAccountError(AccountError):
    code = AccountErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT

    def __init__(self) -> None:
        super().__init__("정지 상태의 계좌만 재개할 수 있습니다.")


class AccountAlreadyClosedError(AccountError):
    code = AccountErrorCode.ACCOUNT_ALREADY_CLOSED

    def __init__(self) -> None:
        super().__init__("이미 종료된 계좌입니다.")


class AccountBalanceNotZeroError(AccountError):
    code = AccountErrorCode.ACCOUNT_BALANCE_NOT_ZERO

    def __init__(self) -> None:
        super().__init__("잔액이 0이 아닌 계좌는 종료할 수 없습니다.")
