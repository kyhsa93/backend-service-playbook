from unittest.mock import AsyncMock

import pytest

from src.account.application.command.deposit_handler import DepositCommand, DepositHandler
from src.account.domain.account import Account
from src.account.domain.errors import AccountNotFoundError, DepositRequiresActiveAccountError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_raises_AccountNotFoundError_when_account_is_missing(repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = DepositHandler(repo)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(DepositCommand(account_id="non-existent", requester_id="owner-1", amount=1000))

    repo.save_account.assert_not_called()


@pytest.mark.asyncio
async def test_execute_calls_save_on_successful_deposit(repo) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositHandler(repo)

    transaction = await handler.execute(
        DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=10000)
    )

    assert transaction.type == "DEPOSIT"
    assert account.balance.amount == 10000
    repo.save_account.assert_awaited_once_with(account)


@pytest.mark.asyncio
async def test_execute_raises_an_error_and_does_not_save_when_account_is_suspended(repo) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.suspend()
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositHandler(repo)

    with pytest.raises(DepositRequiresActiveAccountError):
        await handler.execute(DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=1000))

    repo.save_account.assert_not_called()
