from .account_status import AccountStatus


class Account:
    def __init__(self, status: AccountStatus) -> None:
        self._status = status

    @property
    def status(self) -> AccountStatus:
        return self._status

    @status.setter
    def status(self, value: AccountStatus) -> None:
        self._status = value
