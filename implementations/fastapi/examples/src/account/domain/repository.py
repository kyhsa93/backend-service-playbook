from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountRepository(ABC):

    @abstractmethod
    async def find_by_id(self, account_id: str, owner_id: str) -> Account | None: ...

    @abstractmethod
    async def find_all(
        self,
        page: int,
        take: int,
        account_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def save(self, account: Account) -> None: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...
