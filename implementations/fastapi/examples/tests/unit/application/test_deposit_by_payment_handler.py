from unittest.mock import AsyncMock

import pytest

from src.account.application.command.deposit_by_payment_handler import (
    DepositByPaymentCommand,
    DepositByPaymentHandler,
)
from src.account.domain.account import Account


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


def make_account() -> Account:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()
    return account


@pytest.mark.asyncio
async def test_execute_silently_ignores_an_already_processed_reference_id(repo) -> None:
    repo.has_transaction_with_reference.return_value = True
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id="account-1", amount=1000, reference_id="payment-1"))

    repo.has_transaction_with_reference.assert_awaited_once_with("payment-1", "DEPOSIT")
    repo.find_accounts.assert_not_awaited()
    repo.save_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_runs_the_compensating_credit_and_saves_on_first_processing_of_a_reference_id(repo) -> None:
    account = make_account()
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id=account.account_id, amount=5000, reference_id="payment-1"))

    assert account.balance.amount == 5000
    repo.save_account.assert_awaited_once_with(account)


@pytest.mark.asyncio
async def test_execute_treats_deposit_and_withdrawal_as_different_transactions_even_when_sharing_the_same_payment_id(
    repo,
) -> None:
    # This reaction is a compensating credit (DEPOSIT) that shares the same payment_id as
    # payment.completed.v1 (WITHDRAWAL) as its reference_id — without also checking type, the
    # same reference_id already recorded as WITHDRAWAL would be wrongly judged "already
    # processed" and the compensating credit would be skipped.
    account = make_account()
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id=account.account_id, amount=5000, reference_id="payment-1"))

    repo.has_transaction_with_reference.assert_awaited_once_with("payment-1", "DEPOSIT")
