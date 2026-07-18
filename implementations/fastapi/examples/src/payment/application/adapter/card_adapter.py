from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class CardView:
    """Card BC를 Payment BC가 필요로 하는 최소 형태로 번역한 읽기 뷰.

    상류(Card) 모델의 `CardStatus` enum을 그대로 노출하지 않고 `active: bool`로 축약한다 —
    Card BC가 이미 Account를 이 방식(AccountView)으로 조회하고 있는 것과 동일한 ACL 패턴을
    Payment가 재사용한다.
    """

    card_id: str
    account_id: str
    active: bool


class CardAdapter(ABC):
    """Card BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).

    결제 시 카드가 존재하고 활성 상태인지, 연결된 account_id가 무엇인지를 현재 요청 안에서
    즉시 확인해야 하므로 동기 Adapter 패턴을 사용한다(cross-domain.md 참조). 구현체는
    infrastructure/card_adapter_impl.py 에 있으며, 상류의 "카드 없음"을 Payment 도메인이
    이해하는 None으로 번역한다.
    """

    @abstractmethod
    async def find_card(self, card_id: str, owner_id: str) -> CardView | None: ...
