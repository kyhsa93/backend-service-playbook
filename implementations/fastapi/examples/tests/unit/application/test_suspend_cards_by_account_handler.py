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
async def test_execute_suspends_only_active_cards_and_saves_them(repo) -> None:
    card = Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")
    repo.find_cards.return_value = ([card], 1)
    handler = SuspendCardsByAccountHandler(repo)

    await handler.execute(SuspendCardsByAccountCommand(account_id="account-1"))

    assert card.status == CardStatus.SUSPENDED
    repo.find_cards.assert_awaited_once_with(
        page=0, take=1000, account_id="account-1", status=[CardStatus.ACTIVE.value]
    )
    repo.save_card.assert_awaited_once_with(card)


@pytest.mark.asyncio
async def test_execute_does_nothing_idempotently_when_there_are_no_active_cards(repo) -> None:
    repo.find_cards.return_value = ([], 0)
    handler = SuspendCardsByAccountHandler(repo)

    await handler.execute(SuspendCardsByAccountCommand(account_id="account-1"))

    repo.save_card.assert_not_awaited()
