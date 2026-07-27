from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError, SpendingAnalysisNotFoundError
from ...domain.repository import AccountQuery
from ...domain.spending_analysis_repository import SpendingAnalysisQuery
from .result import SpendingAnalysisResult


@dataclass
class GetSpendingAnalysisQuery:
    account_id: str
    requester_id: str
    analysis_month: str


class GetSpendingAnalysisHandler:
    """Verifies account ownership by reusing AccountQuery.find_accounts — the same helper
    GetAccountHandler/GetTransactionsHandler already use, since Account (like Refund) has an
    owner_id column directly on it, so ownership is a single lookup, not a two-hop
    verification. Unlike nestjs's SpendingAnalysisQuery (a dedicated read-side abstraction
    that re-verifies ownership itself via its own SQL join against AccountEntity), this
    reuses the existing AccountQuery port for that first check instead of introducing a
    redundant one — the same design decision the go port already makes.
    """

    def __init__(self, account_query: AccountQuery, analysis_query: SpendingAnalysisQuery) -> None:
        self._account_query = account_query
        self._analysis_query = analysis_query

    async def execute(self, query: GetSpendingAnalysisQuery) -> SpendingAnalysisResult:
        accounts, _ = await self._account_query.find_accounts(
            page=0, take=1, account_id=query.account_id, owner_id=query.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(query.account_id)

        analysis = await self._analysis_query.find_analysis(query.account_id, query.analysis_month)
        if analysis is None:
            raise SpendingAnalysisNotFoundError(query.account_id, query.analysis_month)

        return SpendingAnalysisResult(
            analysis_month=analysis.analysis_month,
            total_amount=analysis.total_amount,
            transaction_count=analysis.transaction_count,
            average_amount=analysis.average_amount,
            change_from_previous_month=analysis.change_from_previous_month,
            trend=analysis.trend,
            created_at=analysis.created_at,
        )
