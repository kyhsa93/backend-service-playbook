from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository


@dataclass
class SuspendAccountCommand:
    account_id: str
    requester_id: str


class SuspendAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: SuspendAccountCommand) -> None:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        account.suspend()
        await self._repo.save_account(account)
