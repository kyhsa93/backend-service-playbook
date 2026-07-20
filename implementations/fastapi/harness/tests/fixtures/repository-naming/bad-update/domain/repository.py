from abc import ABC, abstractmethod

from .account import Account


class AccountRepository(ABC):
    @abstractmethod
    async def update_status(self, account_id: str, status: str) -> None: ...

    @abstractmethod
    async def save_account(self, account: Account) -> None: ...
