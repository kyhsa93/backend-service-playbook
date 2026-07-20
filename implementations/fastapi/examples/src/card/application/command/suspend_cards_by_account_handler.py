from dataclasses import dataclass

from ...domain.card_status import CardStatus
from ...domain.repository import CardRepository


@dataclass
class SuspendCardsByAccountCommand:
    account_id: str


class SuspendCardsByAccountHandler:
    """Account BC의 account.suspended.v1 Integration Event에 대한 반응 유스케이스.

    at-least-once 전달을 전제로 멱등하게 구현한다 — ACTIVE 카드만 골라 정지하므로
    같은 이벤트가 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다.
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
