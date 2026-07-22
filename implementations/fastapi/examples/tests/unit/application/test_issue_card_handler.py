from unittest.mock import AsyncMock

import pytest

from src.card.application.adapter.account_adapter import AccountView
from src.card.application.command.issue_card_handler import IssueCardCommand, IssueCardHandler
from src.card.domain.card_status import CardStatus
from src.card.domain.errors import (
    CardIssueRequiresActiveAccountError,
    LinkedAccountNotFoundError,
)


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def account_adapter() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_issues_and_saves_a_card_when_account_is_active(repo, account_adapter) -> None:
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=True, email="owner1@example.com"
    )
    handler = IssueCardHandler(repo, account_adapter)

    card = await handler.execute(IssueCardCommand(requester_id="owner-1", account_id="account-1", brand="VISA"))

    assert card.account_id == "account-1"
    assert card.owner_id == "owner-1"
    assert card.status == CardStatus.ACTIVE
    account_adapter.find_account.assert_awaited_once_with("account-1", "owner-1")
    repo.save_card.assert_awaited_once_with(card)


@pytest.mark.asyncio
async def test_execute_raises_LinkedAccountNotFoundError_when_account_to_link_is_missing(repo, account_adapter) -> None:
    account_adapter.find_account.return_value = None
    handler = IssueCardHandler(repo, account_adapter)

    with pytest.raises(LinkedAccountNotFoundError):
        await handler.execute(IssueCardCommand(requester_id="owner-1", account_id="account-1", brand="VISA"))

    repo.save_card.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_raises_CardIssueRequiresActiveAccountError_when_account_is_inactive(
    repo, account_adapter
) -> None:
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=False, email="owner1@example.com"
    )
    handler = IssueCardHandler(repo, account_adapter)

    with pytest.raises(CardIssueRequiresActiveAccountError):
        await handler.execute(IssueCardCommand(requester_id="owner-1", account_id="account-1", brand="VISA"))

    repo.save_card.assert_not_awaited()
