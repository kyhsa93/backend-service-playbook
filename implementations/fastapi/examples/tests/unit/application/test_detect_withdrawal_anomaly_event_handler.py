from unittest.mock import AsyncMock

import pytest

from src.account.application.event.detect_withdrawal_anomaly_event_handler import (
    DetectWithdrawalAnomalyEventHandler,
)
from src.account.domain.events import WithdrawalAnomalyDetected


@pytest.fixture
def transaction_repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def notification_service() -> AsyncMock:
    return AsyncMock()


def make_payload(amount: int = 5000000) -> dict:
    return {
        "account_id": "account-1",
        "email": "owner@example.com",
        "transaction_id": "transaction-1",
        "amount": {"amount": amount, "currency": "KRW"},
        "created_at": "2026-07-28T00:00:00",
        "outbox_event_id": "outbox-event-1",
    }


@pytest.mark.asyncio
async def test_handle_when_the_amount_is_a_statistical_outlier_against_the_accounts_history_then_sends_an_alert(
    transaction_repo: AsyncMock, notification_service: AsyncMock
) -> None:
    transaction_repo.find_recent_withdrawal_amounts.return_value = [10000, 12000, 9000, 11000, 10500, 9500]
    handler = DetectWithdrawalAnomalyEventHandler(transaction_repo, notification_service)

    await handler.handle(make_payload())

    transaction_repo.find_recent_withdrawal_amounts.assert_awaited_once_with("account-1", "transaction-1", 30)
    notification_service.notify.assert_awaited_once()
    sent_event, outbox_event_id = notification_service.notify.await_args.args
    assert isinstance(sent_event, WithdrawalAnomalyDetected)
    assert sent_event.account_id == "account-1"
    assert sent_event.email == "owner@example.com"
    assert outbox_event_id == "outbox-event-1"


@pytest.mark.asyncio
async def test_handle_when_the_amount_is_within_the_accounts_normal_range_then_sends_no_alert(
    transaction_repo: AsyncMock, notification_service: AsyncMock
) -> None:
    transaction_repo.find_recent_withdrawal_amounts.return_value = [4900000, 5100000, 4950000, 5050000, 5000000]
    handler = DetectWithdrawalAnomalyEventHandler(transaction_repo, notification_service)

    await handler.handle(make_payload())

    notification_service.notify.assert_not_awaited()


@pytest.mark.asyncio
async def test_handle_when_the_account_has_fewer_than_5_prior_withdrawals_then_sends_no_alert_regardless_of_amount(
    transaction_repo: AsyncMock, notification_service: AsyncMock
) -> None:
    transaction_repo.find_recent_withdrawal_amounts.return_value = [10000, 12000]
    handler = DetectWithdrawalAnomalyEventHandler(transaction_repo, notification_service)

    await handler.handle(make_payload())

    notification_service.notify.assert_not_awaited()
