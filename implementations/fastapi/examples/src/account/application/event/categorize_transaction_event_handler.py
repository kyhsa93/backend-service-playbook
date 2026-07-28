from __future__ import annotations

import logging

from ...domain.transaction_repository import TransactionRepository
from ..service.transaction_auto_categorizer import TransactionAutoCategorizer

logger = logging.getLogger(__name__)


class CategorizeTransactionEventHandler:
    """Reacts to MoneyWithdrawn (registered in event_handlers.py alongside
    MoneyWithdrawnEventHandler — build_event_handlers() supports multiple subscribers per
    event type) to categorize the transaction's merchant_name asynchronously, off the
    money-movement hot path — the same reasoning WithdrawHandler never calls an LLM directly.
    Inherently Level-1 idempotent (see docs/architecture/domain-events.md): a retried
    delivery just re-runs the same find -> categorize -> save cycle, landing on the same (or
    an equally acceptable) category.
    """

    def __init__(self, categorizer: TransactionAutoCategorizer, transaction_repo: TransactionRepository) -> None:
        self._categorizer = categorizer
        self._transaction_repo = transaction_repo

    async def handle(self, payload: dict) -> None:
        merchant_name = payload.get("merchant_name")
        # Nothing to classify — the requester didn't attach a merchant_name to this withdrawal.
        if not merchant_name:
            return

        transaction = await self._transaction_repo.find_transaction(payload["transaction_id"])
        if transaction is None:
            return

        category = await self._categorizer.categorize(merchant_name, payload["amount"]["amount"])
        await self._transaction_repo.save_transaction(transaction.categorize(category))
        logger.info("Transaction categorized: transaction_id=%s category=%s", payload["transaction_id"], category)
