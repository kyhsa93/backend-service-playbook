from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from .result import GetTransactionsResult, MoneyResult, TransactionSummary


@dataclass
class GetTransactionsQuery:
    account_id: str
    requester_id: str
    page: int
    take: int


class GetTransactionsHandler:

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetTransactionsQuery) -> GetTransactionsResult:
        account = await self._repo.find_by_id(query.account_id, query.requester_id)
        if account is None:
            raise AccountNotFoundError(query.account_id)

        transactions, count = await self._repo.find_transactions(query.account_id, query.page, query.take)

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
