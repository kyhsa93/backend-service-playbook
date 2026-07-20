from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from ...domain.transaction import Transaction


@dataclass
class WithdrawCommand:
    account_id: str
    requester_id: str
    amount: int


class WithdrawHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: WithdrawCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.withdraw(cmd.amount)
        await self._repo.save(account)
        return transaction
