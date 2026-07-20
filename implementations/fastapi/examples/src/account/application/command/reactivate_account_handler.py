from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository


@dataclass
class ReactivateAccountCommand:
    account_id: str
    requester_id: str


class ReactivateAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: ReactivateAccountCommand) -> None:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        account.reactivate()
        await self._repo.save_account(account)
