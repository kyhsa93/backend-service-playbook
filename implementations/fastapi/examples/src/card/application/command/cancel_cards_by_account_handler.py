from dataclasses import dataclass

from ...domain.card_status import CardStatus
from ...domain.repository import CardRepository


@dataclass
class CancelCardsByAccountCommand:
    account_id: str


class CancelCardsByAccountHandler:
    """Account BC의 account.closed.v1 Integration Event에 대한 반응 유스케이스.

    아직 해지되지 않은 카드(ACTIVE·SUSPENDED)만 해지하므로 재수신에 멱등하다.
    """

    def __init__(self, repo: CardRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelCardsByAccountCommand) -> None:
        cards = await self._repo.find_by_account(cmd.account_id, [CardStatus.ACTIVE.value, CardStatus.SUSPENDED.value])
        for card in cards:
            card.cancel()
            await self._repo.save(card)
