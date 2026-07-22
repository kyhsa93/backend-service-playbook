from __future__ import annotations

import logging

from ...application.command.cancel_cards_by_account_handler import (
    CancelCardsByAccountCommand,
    CancelCardsByAccountHandler,
)
from ...application.command.suspend_cards_by_account_handler import (
    SuspendCardsByAccountCommand,
    SuspendCardsByAccountHandler,
)
from ...domain.repository import CardRepository

logger = logging.getLogger(__name__)


class CardIntegrationEventController:
    """An Interface input adapter that receives an Integration Event published by an
    external BC (Account).

    An input boundary at the same location (interface/) as an HTTP Router. It only calls
    its own domain's use case (a Command Handler), and lets exceptions propagate as-is so
    OutboxConsumer doesn't delete the message and instead retries it (→ DLQ).
    """

    def __init__(self, repo: CardRepository) -> None:
        self._repo = repo

    async def on_account_suspended(self, payload: dict) -> None:
        logger.info("Received account.suspended.v1: account_id=%s", payload["account_id"])
        await SuspendCardsByAccountHandler(self._repo).execute(
            SuspendCardsByAccountCommand(account_id=payload["account_id"])
        )

    async def on_account_closed(self, payload: dict) -> None:
        logger.info("Received account.closed.v1: account_id=%s", payload["account_id"])
        await CancelCardsByAccountHandler(self._repo).execute(
            CancelCardsByAccountCommand(account_id=payload["account_id"])
        )
