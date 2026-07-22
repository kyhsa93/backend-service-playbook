from dataclasses import dataclass

from ...domain.card_status import CardStatus
from ...domain.repository import CardRepository


@dataclass
class CancelCardsByAccountCommand:
    account_id: str


class CancelCardsByAccountHandler:
    """The reaction use case for the Account BC's account.closed.v1 Integration Event.

    Only cancels cards that aren't already cancelled (ACTIVE·SUSPENDED), so it's idempotent
    on redelivery.
    """

    def __init__(self, repo: CardRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelCardsByAccountCommand) -> None:
        cards, _ = await self._repo.find_cards(
            page=0,
            take=1000,
            account_id=cmd.account_id,
            status=[CardStatus.ACTIVE.value, CardStatus.SUSPENDED.value],
        )
        for card in cards:
            card.cancel()
            await self._repo.save_card(card)
