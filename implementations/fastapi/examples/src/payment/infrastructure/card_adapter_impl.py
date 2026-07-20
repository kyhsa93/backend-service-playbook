from ...card.domain.card_status import CardStatus
from ...card.domain.repository import CardQuery
from ..application.adapter.card_adapter import CardAdapter, CardView


class CardAdapterImpl(CardAdapter):
    """CardAdapter의 구현체(ACL). Card BC가 공개한 읽기 인터페이스(CardQuery)를 주입받아
    호출하고, Card BC의 모델·상태를 Payment BC가 쓰는 최소 형태(CardView)로 번역한다.
    Card의 Repository 구현체나 도메인 객체를 직접 참조하지 않는다.
    """

    def __init__(self, card_query: CardQuery) -> None:
        self._card_query = card_query

    async def find_card(self, card_id: str, owner_id: str) -> CardView | None:
        cards, _ = await self._card_query.find_cards(page=0, take=1, card_id=card_id, owner_id=owner_id)
        card = cards[0] if cards else None
        # 상류의 "카드 없음"(None)을 그대로 Payment 도메인의 None 신호로 전달한다(오염 방지).
        if card is None:
            return None
        return CardView(card_id=card.card_id, account_id=card.account_id, active=card.status == CardStatus.ACTIVE)
