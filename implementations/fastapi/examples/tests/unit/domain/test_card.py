import pytest

from src.card.domain.card import Card
from src.card.domain.card_status import CardStatus
from src.card.domain.errors import (
    CancelledCardCannotBeSuspendedError,
    CardAlreadyCancelledError,
    CardAlreadySuspendedError,
)


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
