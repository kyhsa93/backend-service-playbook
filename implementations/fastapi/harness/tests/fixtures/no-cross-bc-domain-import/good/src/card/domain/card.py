from __future__ import annotations

from datetime import datetime

from ...common.generate_id import generate_id
from .card_status import CardStatus


class Card:
    def __init__(self, card_id: str, account_id: str, status: CardStatus, created_at: datetime) -> None:
        self.card_id = card_id
        self.account_id = account_id
        self.status = status
        self.created_at = created_at

    @staticmethod
    def issue(account_id: str) -> "Card":
        return Card(card_id=generate_id(), account_id=account_id, status=CardStatus.ACTIVE, created_at=datetime.utcnow())
