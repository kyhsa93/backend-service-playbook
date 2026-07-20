from dataclasses import dataclass

from ...domain.errors import CardNotFoundError
from ...domain.repository import CardQuery
from .result import GetCardResult


@dataclass
class GetCardQuery:
    card_id: str
    requester_id: str


class GetCardHandler:
    def __init__(self, query: CardQuery) -> None:
        self._query = query

    async def execute(self, query: GetCardQuery) -> GetCardResult:
        cards, _ = await self._query.find_cards(page=0, take=1, card_id=query.card_id, owner_id=query.requester_id)
        card = cards[0] if cards else None
        if card is None:
            raise CardNotFoundError(query.card_id)
        return GetCardResult(
            card_id=card.card_id,
            account_id=card.account_id,
            owner_id=card.owner_id,
            brand=card.brand,
            status=card.status.value,
            created_at=card.created_at,
        )
