class AccountError(Exception):
    pass


class AccountNotFoundError(AccountError):
    def __init__(self, account_id: str) -> None:
        super().__init__(f"account not found: {account_id}")
        self.account_id = account_id


class InvalidAmountError(AccountError):
    def __init__(self) -> None:
        super().__init__("금액은 0보다 커야 합니다.")


class InvalidMoneyAmountError(AccountError):
    def __init__(self) -> None:
        super().__init__("금액은 0 이상이어야 합니다.")


class CurrencyMismatchError(AccountError):
    def __init__(self) -> None:
        super().__init__("통화가 일치하지 않습니다.")


class DepositRequiresActiveAccountError(AccountError):
    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 입금할 수 있습니다.")


class WithdrawRequiresActiveAccountError(AccountError):
    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 출금할 수 있습니다.")


class InsufficientBalanceError(AccountError):
    def __init__(self) -> None:
        super().__init__("잔액이 부족합니다.")


class SuspendRequiresActiveAccountError(AccountError):
    def __init__(self) -> None:
        super().__init__("활성 상태의 계좌만 정지할 수 있습니다.")


class ReactivateRequiresSuspendedAccountError(AccountError):
    def __init__(self) -> None:
        super().__init__("정지 상태의 계좌만 재개할 수 있습니다.")


class AccountAlreadyClosedError(AccountError):
    def __init__(self) -> None:
        super().__init__("이미 종료된 계좌입니다.")


class AccountBalanceNotZeroError(AccountError):
    def __init__(self) -> None:
        super().__init__("잔액이 0이 아닌 계좌는 종료할 수 없습니다.")
