from dataclasses import dataclass

from ....outbox.outbox_relay import OutboxRelay
from ...domain.account import Account
from ...domain.repository import AccountRepository


@dataclass
class CreateAccountCommand:
    requester_id: str
    currency: str
    email: str


class CreateAccountHandler:

    def __init__(self, repo: AccountRepository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        account = Account.create(cmd.requester_id, cmd.currency, cmd.email)
        await self._repo.save(account)
        await self._outbox_relay.process_pending()
        return account
