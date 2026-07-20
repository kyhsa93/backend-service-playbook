from dataclasses import dataclass

from ...domain.repository import AccountQuery
from .result import GetAccountResult


@dataclass
class GetAccountQuery:
    account_id: str


class GetAccountHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

    async def execute(self, query: GetAccountQuery) -> GetAccountResult:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=query.account_id)
        account = accounts[0]
        return GetAccountResult(account_id=account.account_id, status=account.status.value)
