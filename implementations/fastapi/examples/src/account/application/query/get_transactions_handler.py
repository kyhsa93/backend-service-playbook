from dataclasses import dataclass
from datetime import date

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountQuery
from ...domain.transaction import TransactionType
from .result import GetTransactionsResult, MoneyResult, TransactionSummary


@dataclass
class GetTransactionsQuery:
    account_id: str
    requester_id: str
    page: int
    take: int
    type: TransactionType | None = None
    from_date: date | None = None
    to_date: date | None = None


class GetTransactionsHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

    async def execute(self, query: GetTransactionsQuery) -> GetTransactionsResult:
        accounts, _ = await self._repo.find_accounts(
            page=0, take=1, account_id=query.account_id, owner_id=query.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(query.account_id)

        transactions, count = await self._repo.find_transactions(
            query.account_id,
            query.page,
            query.take,
            type=query.type,
            from_date=query.from_date,
            to_date=query.to_date,
        )

        return GetTransactionsResult(
            transactions=[
                TransactionSummary(
                    transaction_id=t.transaction_id,
                    type=t.type,
                    amount=MoneyResult(amount=t.amount.amount, currency=t.amount.currency),
                    created_at=t.created_at,
                )
                for t in transactions
            ],
            count=count,
        )
