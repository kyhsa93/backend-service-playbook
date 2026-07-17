from abc import ABC, abstractmethod

from .card import Card


class CardQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `CardRepository`(쓰기 모델)와 계약을 공유하지만 별도 타입이며,
    Query Handler는 반드시 이 타입으로만 의존해야 한다(account 도메인의 AccountQuery와 동일).
    """

    @abstractmethod
    async def find_by_id(self, card_id: str, owner_id: str) -> Card | None: ...


class CardRepository(CardQuery, ABC):
    @abstractmethod
    async def find_by_account(self, account_id: str, status: list[str]) -> list[Card]: ...

    @abstractmethod
    async def save(self, card: Card) -> None: ...
