from dataclasses import dataclass

from ...domain.account import Account
from ...domain.repository import AccountRepository


@dataclass
class CreateAccountCommand:
    requester_id: str
    currency: str


class CreateAccountHandler:

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateAccountCommand) -> Account:
        account = Account.create(cmd.requester_id, cmd.currency)
        await self._repo.save(account)
        return account
