from abc import ABC, abstractmethod

from .spending_analysis import SpendingAnalysis


class SpendingAnalysisQuery(ABC):
    """A read-only interface for the spending_analysis read model — the same
    Query/Repository split as AccountQuery/AccountRepository (domain/repository.py). Never
    exposes a write method such as save_analysis() (see cqrs-pattern.md).
    """

    @abstractmethod
    async def find_analysis(self, account_id: str, analysis_month: str) -> SpendingAnalysis | None: ...

    # A cheap idempotency check ahead of the real work — the (account_id, analysis_month)
    # unique constraint on the table is the last line of defense, the same two-layer pattern
    # as has_transaction_with_reference.
    @abstractmethod
    async def has_analysis(self, account_id: str, analysis_month: str) -> bool: ...


class SpendingAnalysisRepository(SpendingAnalysisQuery, ABC):
    """The write model — extends SpendingAnalysisQuery to reuse its lookup methods, adding
    only save_analysis()."""

    @abstractmethod
    async def save_analysis(self, analysis: SpendingAnalysis) -> None: ...
