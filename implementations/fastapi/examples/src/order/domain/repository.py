from abc import ABC, abstractmethod

from .order import Order


class OrderRepository(ABC):

    @abstractmethod
    async def find_by_id(self, order_id: str) -> Order | None: ...

    @abstractmethod
    async def find_all(
        self,
        page: int,
        take: int,
        user_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Order], int]: ...

    @abstractmethod
    async def save(self, order: Order) -> None: ...

    @abstractmethod
    async def delete(self, order_id: str) -> None: ...
