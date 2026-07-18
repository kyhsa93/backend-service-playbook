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


@pytest.fixture
def outbox_relay() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_활성_카드와_충분한_잔액이면_결제가_완료로_저장된다(
    repo, card_adapter, account_adapter, outbox_relay
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=True, balance_amount=10000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    payment = await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    assert payment.status == PaymentStatus.COMPLETED
    assert payment.account_id == "account-1"
    assert payment.owner_id == "owner-1"
    assert payment.amount == 5000
    repo.save.assert_awaited_once_with(payment)
    outbox_relay.process_pending.assert_awaited_once()


@pytest.mark.asyncio
async def test_execute_카드가_없으면_LinkedCardNotFoundError(repo, card_adapter, account_adapter, outbox_relay) -> None:
    card_adapter.find_card.return_value = None
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    with pytest.raises(LinkedCardNotFoundError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save.assert_not_awaited()
    account_adapter.find_account.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_비활성_카드면_PaymentRequiresActiveCardError(
    repo, card_adapter, account_adapter, outbox_relay
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=False)
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    with pytest.raises(PaymentRequiresActiveCardError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_연결계좌가_없으면_LinkedAccountNotFoundError(
    repo, card_adapter, account_adapter, outbox_relay
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = None
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    with pytest.raises(LinkedAccountNotFoundError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_비활성_계좌면_PaymentRequiresActiveAccountError(
    repo, card_adapter, account_adapter, outbox_relay
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=False, balance_amount=10000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    with pytest.raises(PaymentRequiresActiveAccountError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_잔액이_부족하면_InsufficientBalanceError(
    repo, card_adapter, account_adapter, outbox_relay
) -> None:
    card_adapter.find_card.return_value = CardView(card_id="card-1", account_id="account-1", active=True)
    account_adapter.find_account.return_value = AccountView(
        account_id="account-1", active=True, balance_amount=1000, currency="KRW"
    )
    handler = CreatePaymentHandler(repo, card_adapter, account_adapter, outbox_relay)

    with pytest.raises(InsufficientBalanceError):
        await handler.execute(CreatePaymentCommand(requester_id="owner-1", card_id="card-1", amount=5000))

    repo.save.assert_not_awaited()
