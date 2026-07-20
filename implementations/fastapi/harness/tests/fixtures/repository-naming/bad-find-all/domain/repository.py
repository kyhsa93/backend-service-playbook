from abc import ABC, abstractmethod

from .account import Account


class AccountQuery(ABC):
    @abstractmethod
    async def find_all(self, page: int, take: int) -> tuple[list[Account], int]: ...
