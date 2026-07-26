from __future__ import annotations

from abc import ABC, abstractmethod

from ..query.result import TransactionSummary


class NlTransactionAnswerComposer(ABC):
    """A Technical Service (see root docs/architecture/domain-service.md) generating a
    natural-language answer grounded in already-retrieved transaction records — the "Generate"
    step of a structured-data RAG pipeline. It never queries data itself; the Query Handler
    retrieves the records first (scoped to the authenticated requester) and passes them in
    here as plain data, so this service can never widen what's visible beyond what was already
    fetched.
    """

    @abstractmethod
    async def compose(self, question: str, transactions: list[TransactionSummary]) -> str: ...
