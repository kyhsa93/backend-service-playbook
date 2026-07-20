from abc import ABC, abstractmethod

from .account import Account


class AccountQuery(ABC):
    @abstractmethod
    async def find_accounts(self, page: int, take: int) -> list[Account]: ...

    @abstractmethod
    async def count_by_owner_id(self, owner_id: str) -> int: ...
