from unittest.mock import AsyncMock

import pytest

from src.account.application.command.deposit_handler import DepositCommand, DepositHandler
from src.account.domain.account import Account
from src.account.domain.errors import AccountNotFoundError, DepositRequiresActiveAccountError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def outbox_relay() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_계좌가_없으면_AccountNotFoundError를_던진다(repo, outbox_relay) -> None:
    repo.find_by_id.return_value = None
    handler = DepositHandler(repo, outbox_relay)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(DepositCommand(account_id="non-existent", requester_id="owner-1", amount=1000))

    outbox_relay.process_pending.assert_not_called()


@pytest.mark.asyncio
async def test_execute_입금_성공_시_save와_outbox_드레인이_호출된다(repo, outbox_relay) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()
    repo.find_by_id.return_value = account
    handler = DepositHandler(repo, outbox_relay)

    transaction = await handler.execute(
        DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=10000)
    )

    assert transaction.type == "DEPOSIT"
    assert account.balance.amount == 10000
    repo.save.assert_awaited_once_with(account)
    outbox_relay.process_pending.assert_awaited_once()


@pytest.mark.asyncio
async def test_execute_정지된_계좌면_에러를_던지고_저장하지_않는다(repo, outbox_relay) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.suspend()
    repo.find_by_id.return_value = account
    handler = DepositHandler(repo, outbox_relay)

    with pytest.raises(DepositRequiresActiveAccountError):
        await handler.execute(DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=1000))

    repo.save.assert_not_called()
