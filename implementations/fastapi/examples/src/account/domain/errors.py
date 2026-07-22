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
        super().__init__("The amount must be greater than 0.")


class InvalidMoneyAmountError(AccountError):
    code = AccountErrorCode.INVALID_MONEY_AMOUNT

    def __init__(self) -> None:
        super().__init__("The amount must be at least 0.")


class CurrencyMismatchError(AccountError):
    code = AccountErrorCode.CURRENCY_MISMATCH

    def __init__(self) -> None:
        super().__init__("The currencies do not match.")


class DepositRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only an active account can receive a deposit.")


class WithdrawRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only an active account can make a withdrawal.")


class InsufficientBalanceError(AccountError):
    code = AccountErrorCode.INSUFFICIENT_BALANCE

    def __init__(self) -> None:
        super().__init__("Insufficient balance.")


class SuspendRequiresActiveAccountError(AccountError):
    code = AccountErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only an active account can be suspended.")


class ReactivateRequiresSuspendedAccountError(AccountError):
    code = AccountErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT

    def __init__(self) -> None:
        super().__init__("Only a suspended account can be reactivated.")


class AccountAlreadyClosedError(AccountError):
    code = AccountErrorCode.ACCOUNT_ALREADY_CLOSED

    def __init__(self) -> None:
        super().__init__("The account is already closed.")


class AccountBalanceNotZeroError(AccountError):
    code = AccountErrorCode.ACCOUNT_BALANCE_NOT_ZERO

    def __init__(self) -> None:
        super().__init__("An account with a non-zero balance cannot be closed.")


class TransferSameAccountError(AccountError):
    code = AccountErrorCode.TRANSFER_SAME_ACCOUNT

    def __init__(self) -> None:
        super().__init__("The withdrawal account and deposit account cannot be the same.")
