from abc import ABC, abstractmethod

from .account import Account


class AccountRepository(ABC):
    @abstractmethod
    async def save_account(self, account: Account) -> None: ...

    @abstractmethod
    async def delete(self, account_id: str) -> None: ...
