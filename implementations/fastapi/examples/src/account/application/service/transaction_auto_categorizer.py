from __future__ import annotations

from abc import ABC, abstractmethod

from ...domain.transaction import TransactionCategory


class TransactionAutoCategorizer(ABC):
    """A Technical Service (see root docs/architecture/domain-service.md) wrapping a
    self-hosted LLM call — the same placement/shape as NlTransactionQueryTranslator, just
    classifying a merchant_name + amount into a fixed category instead of translating a
    question into a filter.
    """

    @abstractmethod
    async def categorize(self, merchant_name: str, amount: int) -> TransactionCategory: ...
