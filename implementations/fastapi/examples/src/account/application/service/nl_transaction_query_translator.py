from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date

from ...domain.transaction import TransactionType


@dataclass(frozen=True)
class TransactionFilter:
    """A plain, narrow shape — only the fields that can safely narrow WHAT is returned.
    Deliberately has no `account_id`/`owner_id` field: the Query Handler that calls this
    Technical Service always scopes the lookup to the authenticated requester's own account,
    and never lets a value derived from the LLM's interpretation of free text influence WHO
    the data belongs to.
    """

    type: TransactionType | None = None
    from_date: date | None = None  # inclusive
    to_date: date | None = None  # inclusive


class NlTransactionQueryTranslator(ABC):
    """A Technical Service (see root docs/architecture/domain-service.md) translating a
    free-text question about an account's transaction history into a structured filter. This
    is the "Retrieve"-preparation step of a structured-data RAG pipeline:
    NlTransactionAnswerComposer (the "Generate" step) is the other half, and
    AccountQuery.find_transactions itself is the "Retrieve" step in between.
    """

    @abstractmethod
    async def translate(self, question: str) -> TransactionFilter: ...
