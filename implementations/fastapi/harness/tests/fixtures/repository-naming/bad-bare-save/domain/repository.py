from abc import ABC, abstractmethod

from .account import Account


class AccountRepository(ABC):
    @abstractmethod
    async def save(self, account: Account) -> None: ...
