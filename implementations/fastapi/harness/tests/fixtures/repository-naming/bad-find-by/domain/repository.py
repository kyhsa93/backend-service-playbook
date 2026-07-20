from abc import ABC, abstractmethod

from .account import Account


class AccountRepository(ABC):
    @abstractmethod
    async def find_by_id(self, account_id: str) -> Account | None: ...

    @abstractmethod
    async def save_account(self, account: Account) -> None: ...
