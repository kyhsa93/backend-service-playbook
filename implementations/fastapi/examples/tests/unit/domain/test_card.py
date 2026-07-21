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


def test_issue_카드_발급_시_ACTIVE_상태로_생성된다() -> None:
    card = make_active_card()

    assert card.status == CardStatus.ACTIVE
    assert card.account_id == "account-1"
    assert card.owner_id == "owner-1"
    assert card.brand == "VISA"
    assert card.card_id
    assert card.created_at


def test_suspend_활성_카드는_정지된다() -> None:
    card = make_active_card()

    card.suspend()

    assert card.status == CardStatus.SUSPENDED


def test_suspend_이미_정지된_카드는_다시_정지할_수_없다() -> None:
    card = make_active_card()
    card.suspend()

    with pytest.raises(CardAlreadySuspendedError):
        card.suspend()


def test_suspend_해지된_카드는_정지할_수_없다() -> None:
    card = make_active_card()
    card.cancel()

    with pytest.raises(CancelledCardCannotBeSuspendedError):
        card.suspend()


def test_cancel_활성_카드는_해지된다() -> None:
    card = make_active_card()

    card.cancel()

    assert card.status == CardStatus.CANCELLED


def test_cancel_정지된_카드도_해지할_수_있다() -> None:
    card = make_active_card()
    card.suspend()

    card.cancel()

    assert card.status == CardStatus.CANCELLED


def test_cancel_이미_해지된_카드는_다시_해지할_수_없다() -> None:
    card = make_active_card()
    card.cancel()

    with pytest.raises(CardAlreadyCancelledError):
        card.cancel()


def test_send_statement_처음_발송하면_CardStatementSent_이벤트가_수집되고_월이_기록된다() -> None:
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


def test_send_statement_같은_달에_두_번_호출하면_두_번째는_완전한_no_op이다() -> None:
    card = make_active_card()
    card.send_statement("2026-07", payment_count=3, total_amount=15000, email="owner1@example.com")
    card.pull_events()

    card.send_statement("2026-07", payment_count=99, total_amount=999999, email="owner1@example.com")

    assert card.pull_events() == []


def test_send_statement_다음_달에_다시_호출하면_새_이벤트가_수집된다() -> None:
    card = make_active_card()
    card.send_statement("2026-07", payment_count=3, total_amount=15000, email="owner1@example.com")
    card.pull_events()

    card.send_statement("2026-08", payment_count=1, total_amount=500, email="owner1@example.com")

    assert card.last_statement_sent_month == "2026-08"
    events = card.pull_events()
    assert len(events) == 1
    assert events[0].period == "2026-08"
