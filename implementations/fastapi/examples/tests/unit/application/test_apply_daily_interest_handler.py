from datetime import date
from decimal import Decimal
from unittest.mock import AsyncMock

import pytest

from src.account.application.command.apply_daily_interest_handler import ApplyDailyInterestHandler
from src.account.domain.account import Account

TODAY = date(2026, 7, 21)
RATE = Decimal("0.0001")


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


def _active_account(balance: int) -> Account:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()
    if balance:
        account.deposit(balance)
        account.pull_events()
        account.pull_pending_transactions()
    return account


@pytest.mark.asyncio
async def test_execute_ACTIVE_계좌_전체를_순회하며_이자를_지급하고_모두_저장한다(repo) -> None:
    account1 = _active_account(1000000)
    account2 = _active_account(2000000)
    repo.find_accounts.side_effect = [([account1, account2], 2), ([], 2)]
    handler = ApplyDailyInterestHandler(repo)

    applied_count = await handler.execute(RATE, TODAY)

    assert applied_count == 2
    assert account1.balance.amount == 1000100
    assert account2.balance.amount == 2000200
    assert repo.save_account.await_count == 2
    repo.find_accounts.assert_any_call(page=0, take=200, status=["ACTIVE"])


@pytest.mark.asyncio
async def test_execute_이자가_0원인_계좌는_applied_count에_포함되지_않지만_저장은_된다(repo) -> None:
    tiny_account = _active_account(1)
    repo.find_accounts.side_effect = [([tiny_account], 1), ([], 1)]
    handler = ApplyDailyInterestHandler(repo)

    applied_count = await handler.execute(RATE, TODAY)

    assert applied_count == 0
    repo.save_account.assert_awaited_once_with(tiny_account)


@pytest.mark.asyncio
async def test_execute_ACTIVE_계좌가_없으면_아무것도_저장하지_않는다(repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = ApplyDailyInterestHandler(repo)

    applied_count = await handler.execute(RATE, TODAY)

    assert applied_count == 0
    repo.save_account.assert_not_called()
