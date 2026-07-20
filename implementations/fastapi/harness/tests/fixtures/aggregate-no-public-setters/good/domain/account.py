from .account_status import AccountStatus


class Account:
    def __init__(self, status: AccountStatus) -> None:
        self.status = status

    def suspend(self) -> None:
        self.status = AccountStatus.SUSPENDED

    @property
    def is_active(self) -> bool:
        return self.status == AccountStatus.ACTIVE
