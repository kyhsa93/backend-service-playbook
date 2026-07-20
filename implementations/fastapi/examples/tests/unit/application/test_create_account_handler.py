from unittest.mock import AsyncMock

import pytest

from src.account.application.command.create_account_handler import CreateAccountCommand, CreateAccountHandler


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_계좌_생성_시_저장되고_초기_잔액_0이_담긴다(repo) -> None:
    handler = CreateAccountHandler(repo)

    account = await handler.execute(
        CreateAccountCommand(requester_id="owner-1", currency="KRW", email="owner1@example.com")
    )

    assert account.owner_id == "owner-1"
    assert account.balance.amount == 0
    repo.save.assert_awaited_once_with(account)
