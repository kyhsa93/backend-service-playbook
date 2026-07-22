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
async def test_execute_iterates_all_active_accounts_pays_interest_and_saves_all(repo) -> None:
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
async def test_execute_an_account_with_zero_interest_is_excluded_from_applied_count_but_still_saved(repo) -> None:
    tiny_account = _active_account(1)
    repo.find_accounts.side_effect = [([tiny_account], 1), ([], 1)]
    handler = ApplyDailyInterestHandler(repo)

    applied_count = await handler.execute(RATE, TODAY)

    assert applied_count == 0
    repo.save_account.assert_awaited_once_with(tiny_account)


@pytest.mark.asyncio
async def test_execute_saves_nothing_when_there_are_no_active_accounts(repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = ApplyDailyInterestHandler(repo)

    applied_count = await handler.execute(RATE, TODAY)

    assert applied_count == 0
    repo.save_account.assert_not_called()
