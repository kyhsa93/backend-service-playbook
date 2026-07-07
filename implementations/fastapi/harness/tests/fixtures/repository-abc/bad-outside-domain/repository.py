from abc import ABC, abstractmethod


class AccountRepository(ABC):
    @abstractmethod
    async def save(self, account) -> None: ...
