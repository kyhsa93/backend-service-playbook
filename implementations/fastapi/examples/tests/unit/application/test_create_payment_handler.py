from unittest.mock import AsyncMock

import pytest

from src.payment.application.adapter.account_adapter import AccountView
from src.payment.application.adapter.card_adapter import CardView
from src.payment.application.command.create_payment_handler import CreatePaymentCommand, CreatePaymentHandler
from src.payment.domain.errors import (
    InsufficientBalanceError,
    LinkedAccountNotFoundError,
    LinkedCardNotFoundError,
    PaymentRequiresActiveAccountError,
    PaymentRequiresActiveCardError,
)
from src.payment.domain.payment_status import PaymentStatus


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def card_adapter() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def account_adapter() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_saves_payment_as_completed_when_card_is_active_and_balance_is_sufficient(
    repo, card_adapter, account_adapter
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=True, balance_amount=10000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    payment = await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    assert payment.status == PaymentStatus.COMPLETED
    assert payment.account_id == "account-1"
    assert payment.owner_id == "owner-1"
    assert payment.amount == 5000
    repo.save_payment.assert_awaited_once_with(payment)


@pytest.mark.asyncio
async def test_execute_raises_LinkedCardNotFoundError_when_card_is_missing(repo, card_adapter, account_adapter) -> None:
    card_adapter.find_card.return_value = None
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    with pytest.raises(LinkedCardNotFoundError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save_payment.assert_not_awaited()
    account_adapter.find_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_raises_PaymentRequiresActiveCardError_when_card_is_inactive(
    repo, card_adapter, account_adapter
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=False)
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    with pytest.raises(PaymentRequiresActiveCardError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save_payment.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_raises_LinkedAccountNotFoundError_when_linked_account_is_missing(
    repo, card_adapter, account_adapter
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = None
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    with pytest.raises(LinkedAccountNotFoundError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save_payment.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_raises_PaymentRequiresActiveAccountError_when_account_is_inactive(
    repo, card_adapter, account_adapter
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=False, balance_amount=10000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    with pytest.raises(PaymentRequiresActiveAccountError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save_payment.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_raises_InsufficientBalanceError_when_balance_is_insufficient(
    repo, card_adapter, account_adapter
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=True, balance_amount=1000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter)

    with pytest.raises(InsufficientBalanceError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save_payment.assert_not_awaited()
