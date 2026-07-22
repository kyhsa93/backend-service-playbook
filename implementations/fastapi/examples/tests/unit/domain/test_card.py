import pytest

from src.card.domain.card import Card
from src.card.domain.card_status import CardStatus
from src.card.domain.errors import (
    CancelledCardCannotBeSuspendedError,
    CardAlreadyCancelledError,
    CardAlreadySuspendedError,
)
from src.card.domain.events import CardStatementSent


def make_active_card() -> Card:
    return Card.issue(account_id="account-1", owner_id="owner-1", brand="VISA")


def test_issue_creates_a_card_in_ACTIVE_status() -> None:
    card = make_active_card()

    assert card.status == CardStatus.ACTIVE
    assert card.account_id == "account-1"
    assert card.owner_id == "owner-1"
    assert card.brand == "VISA"
    assert card.card_id
    assert card.created_at


def test_suspend_an_active_card_is_suspended() -> None:
    card = make_active_card()

    card.suspend()

    assert card.status == CardStatus.SUSPENDED


def test_suspend_an_already_suspended_card_cannot_be_suspended_again() -> None:
    card = make_active_card()
    card.suspend()

    with pytest.raises(CardAlreadySuspendedError):
        card.suspend()


def test_suspend_a_cancelled_card_cannot_be_suspended() -> None:
    card = make_active_card()
    card.cancel()

    with pytest.raises(CancelledCardCannotBeSuspendedError):
        card.suspend()


def test_cancel_an_active_card_is_cancelled() -> None:
    card = make_active_card()

    card.cancel()

    assert card.status == CardStatus.CANCELLED


def test_cancel_a_suspended_card_can_also_be_cancelled() -> None:
    card = make_active_card()
    card.suspend()

    card.cancel()

    assert card.status == CardStatus.CANCELLED


def test_cancel_an_already_cancelled_card_cannot_be_cancelled_again() -> None:
    card = make_active_card()
    card.cancel()

    with pytest.raises(CardAlreadyCancelledError):
        card.cancel()


def test_send_statement_collects_a_CardStatementSent_event_and_records_the_month_on_first_send() -> None:
    card = make_active_card()

    card.send_statement("2026-07", payment_count=3, total_amount=15000, email="owner1@example.com")

    assert card.last_statement_sent_month == "2026-07"
    events = card.pull_events()
    assert len(events) == 1
    assert isinstance(events[0], CardStatementSent)
    assert events[0].card_id == card.card_id
    assert events[0].payment_count == 3
    assert events[0].total_amount == 15000
    assert events[0].email == "owner1@example.com"


def test_send_statement_calling_twice_in_the_same_month_makes_the_second_call_a_complete_no_op() -> None:
    card = make_active_card()
    card.send_statement("2026-07", payment_count=3, total_amount=15000, email="owner1@example.com")
    card.pull_events()

    card.send_statement("2026-07", payment_count=99, total_amount=999999, email="owner1@example.com")

    assert card.pull_events() == []


def test_send_statement_calling_again_next_month_collects_a_new_event() -> None:
    card = make_active_card()
    card.send_statement("2026-07", payment_count=3, total_amount=15000, email="owner1@example.com")
    card.pull_events()

    card.send_statement("2026-08", payment_count=1, total_amount=500, email="owner1@example.com")

    assert card.last_statement_sent_month == "2026-08"
    events = card.pull_events()
    assert len(events) == 1
    assert events[0].period == "2026-08"
