from abc import ABC, abstractmethod

from .transaction import Transaction


class TransactionRepository(ABC):
    """Separate from AccountRepository — that one only ever inserts Transaction rows in bulk
    as a side effect of save_account (Transaction rows are otherwise insert-only there). This
    is the find -> modify-via-domain-method -> save_<noun> cycle
    CategorizeTransactionEventHandler needs for the one field (category) that legitimately
    gets set after the fact (see repository-pattern.md's "a Repository must not have an
    update method" rule).
    """

    @abstractmethod
    async def find_transaction(self, transaction_id: str) -> Transaction | None: ...

    @abstractmethod
    async def save_transaction(self, transaction: Transaction) -> None: ...

    @abstractmethod
    async def find_recent_withdrawal_amounts(
        self, account_id: str, exclude_transaction_id: str, limit: int
    ) -> list[int]:
        """The training data for AnomalyDetectionService — the account's own recent
        WITHDRAWAL amounts (order doesn't matter here, unlike SpendingAnalysisRepository's
        history queries, since the Domain Service only computes a mean/stddev over the set).

        exclude_transaction_id is the withdrawal currently being judged — by the time
        DetectWithdrawalAnomalyEventHandler runs (after the Outbox has delivered
        MoneyWithdrawn), that transaction is already persisted, so it must be excluded or it
        would skew its own baseline.
        """
        ...
