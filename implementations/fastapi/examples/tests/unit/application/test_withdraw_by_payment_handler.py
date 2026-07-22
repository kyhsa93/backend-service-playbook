from unittest.mock import AsyncMock

import pytest

from src.account.application.command.withdraw_by_payment_handler import (
    WithdrawByPaymentCommand,
    WithdrawByPaymentHandler,
)
from src.account.domain.account import Account


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


def make_account(balance: int = 10000) -> Account:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()
    if balance:
        account.deposit(balance)
        account.pull_events()
        account.pull_pending_transactions()
    return account


@pytest.mark.asyncio
async def test_execute_silently_ignores_an_already_processed_reference_id(repo) -> None:
    repo.has_transaction_with_reference.return_value = True
    handler = WithdrawByPaymentHandler(repo)

    await handler.execute(WithdrawByPaymentCommand(account_id="account-1", amount=1000, reference_id="payment-1"))

    repo.has_transaction_with_reference.assert_awaited_once_with("payment-1", "WITHDRAWAL")
    repo.find_accounts.assert_not_awaited()
    repo.save_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_silently_ignores_when_account_is_missing(repo) -> None:
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([], 0)
    handler = WithdrawByPaymentHandler(repo)

    await handler.execute(WithdrawByPaymentCommand(account_id="account-1", amount=1000, reference_id="payment-1"))

    repo.save_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_withdraws_and_saves_on_first_processing_of_a_reference_id(repo) -> None:
    account = make_account(balance=10000)
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([account], 1)
    handler = WithdrawByPaymentHandler(repo)

    await handler.execute(
        WithdrawByPaymentCommand(account_id=account.account_id, amount=3000, reference_id="payment-1")
    )

    assert account.balance.amount == 7000
    repo.save_account.assert_awaited_once_with(account)
