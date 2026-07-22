from dataclasses import dataclass

from ...domain.account import Account
from ...domain.repository import AccountRepository


@dataclass
class CreateAccountCommand:
    requester_id: str
    currency: str
    email: str


class CreateAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        account = Account.create(cmd.requester_id, cmd.currency, cmd.email)
        await self._repo.save_account(account)
        # Returns immediately after saving — publishing/receiving Outbox → SQS is the
        # sole responsibility of the independently, periodically running
        # OutboxPoller/OutboxConsumer (domain-events.md).
        return account
