from unittest.mock import AsyncMock

import pytest

from src.card.application.command.suspend_cards_by_account_handler import (
    SuspendCardsByAccountCommand,
    SuspendCardsByAccountHandler,
)
from src.card.domain.card import Card
from src.card.domain.card_status import CardStatus


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_ACTIVE_카드만_정지시키고_저장한다(repo) -> None:
    card = Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")
    repo.find_by_account.return_value = [card]
    handler = SuspendCardsByAccountHandler(repo)

    await handler.execute(SuspendCardsByAccountCommand(account_id="account-1"))

    assert card.status == CardStatus.SUSPENDED
    repo.find_by_account.assert_awaited_once_with("account-1", [CardStatus.ACTIVE.value])
    repo.save.assert_awaited_once_with(card)


@pytest.mark.asyncio
async def test_execute_ACTIVE_카드가_없으면_멱등하게_아무_일도_하지_않는다(repo) -> None:
    repo.find_by_account.return_value = []
    handler = SuspendCardsByAccountHandler(repo)

    await handler.execute(SuspendCardsByAccountCommand(account_id="account-1"))

    repo.save.assert_not_awaited()
