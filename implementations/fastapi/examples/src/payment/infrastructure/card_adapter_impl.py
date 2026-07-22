from ...card.domain.card_status import CardStatus
from ...card.domain.repository import CardQuery
from ..application.adapter.card_adapter import CardAdapter, CardView


class CardAdapterImpl(CardAdapter):
    """The implementation of CardAdapter (ACL). It's injected with and calls the read
    interface (CardQuery) the Card BC exposes, and translates Card BC's model/status into
    the minimal shape the Payment BC uses (CardView). It never references Card's
    Repository implementation or domain objects directly.
    """

    def __init__(self, card_query: CardQuery) -> None:
        self._card_query = card_query

    async def find_card(self, card_id: str, owner_id: str) -> CardView | None:
        cards, _ = await self._card_query.find_cards(page=0, take=1, card_id=card_id, owner_id=owner_id)
        card = cards[0] if cards else None
        # Passes the upstream "card not found" (None) straight through as Payment domain's None signal
        # (prevents leaking upstream model details).
        if card is None:
            return None
        return CardView(card_id=card.card_id, account_id=card.account_id, active=card.status == CardStatus.ACTIVE)
