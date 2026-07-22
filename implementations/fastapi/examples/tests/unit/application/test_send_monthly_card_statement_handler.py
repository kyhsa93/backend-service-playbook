from datetime import date, datetime
from unittest.mock import AsyncMock

import pytest

from src.card.application.adapter.account_adapter import AccountView
from src.card.application.adapter.payment_adapter import CardPaymentSummary
from src.card.application.command.send_monthly_card_statement_handler import (
    SendMonthlyCardStatementHandler,
    previous_month_period,
)
from src.card.domain.card import Card

TODAY = date(2026, 8, 1)


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def account_adapter() -> AsyncMock:
    adapter = AsyncMock()
    adapter.find_account.return_value = AccountView(account_id="account-1", active=True, email="owner1@example.com")
    return adapter


@pytest.fixture
def payment_adapter() -> AsyncMock:
    adapter = AsyncMock()
    adapter.summarize_payments.return_value = CardPaymentSummary(payment_count=3, total_amount=15000)
    return adapter


def _active_card() -> Card:
    return Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")


def test_previous_month_period_computes_the_full_previous_month_range_from_the_1st_of_this_month() -> None:
    period, since, until = previous_month_period(date(2026, 8, 1))

    assert period == "2026-07"
    assert since == datetime(2026, 7, 1)
    assert until == datetime(2026, 8, 1)


def test_previous_month_period_january_becomes_december_of_the_previous_year() -> None:
    period, since, until = previous_month_period(date(2026, 1, 15))

    assert period == "2025-12"
    assert since == datetime(2025, 12, 1)
    assert until == datetime(2026, 1, 1)


@pytest.mark.asyncio
async def test_execute_sends_this_months_unsent_statement_to_all_active_cards(
    repo, account_adapter, payment_adapter
) -> None:
    card1 = _active_card()
    card2 = _active_card()
    repo.find_cards.side_effect = [([card1, card2], 2), ([], 2)]
    handler = SendMonthlyCardStatementHandler(repo, account_adapter, payment_adapter)

    processed_count = await handler.execute(TODAY)

    assert processed_count == 2
    assert card1.last_statement_sent_month == "2026-07"
    assert card2.last_statement_sent_month == "2026-07"
    assert repo.save_card.await_count == 2
    payment_adapter.summarize_payments.assert_any_call(card1.card_id, datetime(2026, 7, 1), datetime(2026, 8, 1))


@pytest.mark.asyncio
async def test_execute_skips_cards_already_sent_this_month(repo, account_adapter, payment_adapter) -> None:
    card = _active_card()
    card.send_statement("2026-07", payment_count=1, total_amount=100, email="owner1@example.com")
    card.pull_events()
    repo.find_cards.side_effect = [([card], 1), ([], 1)]
    handler = SendMonthlyCardStatementHandler(repo, account_adapter, payment_adapter)

    processed_count = await handler.execute(TODAY)

    assert processed_count == 0
    repo.save_card.assert_not_called()
    payment_adapter.summarize_payments.assert_not_called()


@pytest.mark.asyncio
async def test_execute_skips_without_changing_state_when_linked_account_cannot_be_found(
    repo, account_adapter, payment_adapter
) -> None:
    card = _active_card()
    account_adapter.find_account.return_value = None
    repo.find_cards.side_effect = [([card], 1), ([], 1)]
    handler = SendMonthlyCardStatementHandler(repo, account_adapter, payment_adapter)

    processed_count = await handler.execute(TODAY)

    assert processed_count == 0
    assert card.last_statement_sent_month is None
    repo.save_card.assert_not_called()
