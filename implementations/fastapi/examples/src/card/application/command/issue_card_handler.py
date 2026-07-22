from dataclasses import dataclass

from ...domain.card import Card
from ...domain.errors import (
    CardIssueRequiresActiveAccountError,
    LinkedAccountNotFoundError,
)
from ...domain.repository import CardRepository
from ..adapter.account_adapter import AccountAdapter


@dataclass
class IssueCardCommand:
    requester_id: str
    account_id: str
    brand: str


class IssueCardHandler:
    def __init__(self, repo: CardRepository, account_adapter: AccountAdapter) -> None:
        self._repo = repo
        self._account_adapter = account_adapter

    async def execute(self, cmd: IssueCardCommand) -> Card:
        # Looks up the linked account via the synchronous Adapter (ACL) — a synchronous call since the
        # response (whether issuance is allowed) needs it.
        account = await self._account_adapter.find_account(cmd.account_id, cmd.requester_id)
        if account is None:
            raise LinkedAccountNotFoundError()
        if not account.active:
            raise CardIssueRequiresActiveAccountError()

        card = Card.issue(account_id=cmd.account_id, owner_id=cmd.requester_id, brand=cmd.brand)
        await self._repo.save_card(card)
        return card
