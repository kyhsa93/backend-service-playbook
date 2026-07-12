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
    """외부 BC(Account)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.

    HTTP Router와 동일한 위치(interface/)의 입력 경계다. 자기 도메인의 유스케이스(Command
    Handler)만 호출하고, 예외는 그대로 전파해 OutboxRelay가 재시도를 담당하게 한다.
    """

    def __init__(self, repo: CardRepository) -> None:
        self._repo = repo

    async def on_account_suspended(self, payload: dict) -> None:
        logger.info("account.suspended.v1 수신: account_id=%s", payload["account_id"])
        await SuspendCardsByAccountHandler(self._repo).execute(
            SuspendCardsByAccountCommand(account_id=payload["account_id"])
        )

    async def on_account_closed(self, payload: dict) -> None:
        logger.info("account.closed.v1 수신: account_id=%s", payload["account_id"])
        await CancelCardsByAccountHandler(self._repo).execute(
            CancelCardsByAccountCommand(account_id=payload["account_id"])
        )
