from dataclasses import dataclass

from ...domain.account import Account
from ...domain.repository import AccountQuery


@dataclass
class GetAccountQuery:
    account_id: str


class GetAccountHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

    async def execute(self, query: GetAccountQuery) -> Account:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=query.account_id)
        return accounts[0]
