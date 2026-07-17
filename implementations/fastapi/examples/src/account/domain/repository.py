from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `AccountRepository`(쓰기 모델)와 메서드 시그니처를 공유하지만
    별도 계약이다 — Query Handler는 반드시 이 타입으로만 의존해야 한다.
    """

    @abstractmethod
    async def find_by_id(self, account_id: str, owner_id: str) -> Account | None: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
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
