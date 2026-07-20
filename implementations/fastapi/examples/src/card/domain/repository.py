from abc import ABC, abstractmethod

from .card import Card


class CardQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save_card()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `CardRepository`(쓰기 모델)와 계약을 공유하지만 별도 타입이며,
    Query Handler는 반드시 이 타입으로만 의존해야 한다(account 도메인의 AccountQuery와 동일).

    조회 메서드는 단일 `find_cards(...)`로 통일한다(account 도메인의 find_accounts,
    payment 도메인의 find_payments와 동일한 컨벤션) — 과거 find_by_id/find_by_account로
    나뉘어 있던 것을 단일 메서드+선택적 필터 키워드 인자로 통일했다. `take=1`로 단건 조회를
    표현한다.
    """

    @abstractmethod
    async def find_cards(
        self,
        page: int,
        take: int,
        card_id: str | None = None,
        owner_id: str | None = None,
        account_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[Card], int]: ...


class CardRepository(CardQuery, ABC):
    @abstractmethod
    async def save_card(self, card: Card) -> None: ...
