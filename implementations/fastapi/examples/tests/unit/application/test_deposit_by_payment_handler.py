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
async def test_execute_이미_처리된_reference_id면_조용히_무시한다(repo) -> None:
    repo.has_transaction_with_reference.return_value = True
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id="account-1", amount=1000, reference_id="payment-1"))

    repo.has_transaction_with_reference.assert_awaited_once_with("payment-1", "DEPOSIT")
    repo.find_accounts.assert_not_awaited()
    repo.save_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_처음_처리하는_reference_id면_보상_크레딧을_실행하고_저장한다(repo) -> None:
    account = make_account()
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id=account.account_id, amount=5000, reference_id="payment-1"))

    assert account.balance.amount == 5000
    repo.save_account.assert_awaited_once_with(account)


@pytest.mark.asyncio
async def test_execute_같은_payment_id를_공유해도_DEPOSIT과_WITHDRAWAL은_서로_다른_거래로_취급된다(repo) -> None:
    # 이 반응은 payment.completed.v1(WITHDRAWAL)과 같은 payment_id를 reference_id로
    # 공유하는 보상 크레딧(DEPOSIT)이다 — type도 함께 확인하지 않으면 이미 WITHDRAWAL로
    # 기록된 같은 reference_id를 "이미 처리됨"으로 잘못 판정해 보상 크레딧이 스킵된다.
    account = make_account()
    repo.has_transaction_with_reference.return_value = False
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositByPaymentHandler(repo)

    await handler.execute(DepositByPaymentCommand(account_id=account.account_id, amount=5000, reference_id="payment-1"))

    repo.has_transaction_with_reference.assert_awaited_once_with("payment-1", "DEPOSIT")
