from dataclasses import dataclass

from ...domain.card_status import CardStatus
from ...domain.repository import CardRepository


@dataclass
class SuspendCardsByAccountCommand:
    account_id: str


class SuspendCardsByAccountHandler:
    """The reaction use case for the Account BC's account.suspended.v1 Integration Event.

    Implemented to be idempotent, assuming at-least-once delivery — since only ACTIVE cards
    are picked and suspended, nothing happens even if the same event is redelivered (the
    card is already suspended).
    """

    def __init__(self, repo: CardRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: SuspendCardsByAccountCommand) -> None:
        cards, _ = await self._repo.find_cards(
            page=0, take=1000, account_id=cmd.account_id, status=[CardStatus.ACTIVE.value]
        )
        for card in cards:
            card.suspend()
            await self._repo.save_card(card)
