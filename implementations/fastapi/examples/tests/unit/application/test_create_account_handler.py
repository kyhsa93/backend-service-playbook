from unittest.mock import AsyncMock

import pytest

from src.account.application.command.create_account_handler import CreateAccountCommand, CreateAccountHandler


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_saves_the_account_with_an_initial_zero_balance_on_creation(repo) -> None:
    handler = CreateAccountHandler(repo)

    account = await handler.execute(
        CreateAccountCommand(requester_id="owner-1", currency="KRW", email="owner1@example.com")
    )

    assert account.owner_id == "owner-1"
    assert account.balance.amount == 0
    repo.save_account.assert_awaited_once_with(account)
