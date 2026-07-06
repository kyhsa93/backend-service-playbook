from dataclasses import dataclass

from ....outbox.outbox_relay import OutboxRelay
from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository


@dataclass
class SuspendAccountCommand:
    account_id: str
    requester_id: str


class SuspendAccountHandler:

    def __init__(self, repo: AccountRepository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: SuspendAccountCommand) -> None:
        account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        account.suspend()
        await self._repo.save(account)
        await self._outbox_relay.process_pending()
