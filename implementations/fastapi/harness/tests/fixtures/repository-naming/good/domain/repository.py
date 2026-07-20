from abc import ABC, abstractmethod

from .account import Account


class AccountQuery(ABC):
    @abstractmethod
    async def find_accounts(
        self,
        page: int,
        take: int,
        account_id: str | None = None,
        owner_id: str | None = None,
    ) -> tuple[list[Account], int]: ...


class AccountRepository(AccountQuery, ABC):
    @abstractmethod
    async def save_account(self, account: Account) -> None: ...

    @abstractmethod
    async def delete_account(self, account_id: str) -> None: ...

    @abstractmethod
    async def has_transaction_with_reference(self, reference_id: str, type: str) -> bool: ...
