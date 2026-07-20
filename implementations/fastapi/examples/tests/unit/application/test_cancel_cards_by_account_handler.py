from unittest.mock import AsyncMock

import pytest

from src.card.application.command.cancel_cards_by_account_handler import (
    CancelCardsByAccountCommand,
    CancelCardsByAccountHandler,
)
from src.card.domain.card import Card
from src.card.domain.card_status import CardStatus


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_ACTIVE_SUSPENDED_카드를_해지시키고_저장한다(repo) -> None:
    active_card = Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")
    suspended_card = Card.issue(account_id="account-1", owner_id="owner-1", brand="MASTER")
    suspended_card.suspend()
    repo.find_cards.return_value = ([active_card, suspended_card], 2)
    handler = CancelCardsByAccountHandler(repo)

    await handler.execute(CancelCardsByAccountCommand(account_id="account-1"))

    assert active_card.status == CardStatus.CANCELLED
    assert suspended_card.status == CardStatus.CANCELLED
    repo.find_cards.assert_awaited_once_with(
        page=0, take=1000, account_id="account-1", status=[CardStatus.ACTIVE.value, CardStatus.SUSPENDED.value]
    )
    assert repo.save_card.await_count == 2


@pytest.mark.asyncio
async def test_execute_해지_대상_카드가_없으면_멱등하게_아무_일도_하지_않는다(repo) -> None:
    repo.find_cards.return_value = ([], 0)
    handler = CancelCardsByAccountHandler(repo)

    await handler.execute(CancelCardsByAccountCommand(account_id="account-1"))

    repo.save_card.assert_not_awaited()
