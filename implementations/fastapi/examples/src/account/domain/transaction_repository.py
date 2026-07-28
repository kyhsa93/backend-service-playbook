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
