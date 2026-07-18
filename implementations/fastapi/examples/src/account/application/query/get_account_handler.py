from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountQuery
from .result import GetAccountResult, MoneyResult


@dataclass
class GetAccountQuery:
    account_id: str
    requester_id: str


class GetAccountHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

    async def execute(self, query: GetAccountQuery) -> GetAccountResult:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=query.account_id, owner_id=query.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(query.account_id)
        return GetAccountResult(
            account_id=account.account_id,
            owner_id=account.owner_id,
            email=account.email,
            balance=MoneyResult(amount=account.balance.amount, currency=account.balance.currency),
            status=account.status.value,
            created_at=account.created_at,
            updated_at=account.updated_at,
        )
