from __future__ import annotations

from datetime import datetime

from ...payment.domain.payment import Payment


class Card:
    def __init__(self, card_id: str, account_id: str, created_at: datetime, payment: Payment | None = None) -> None:
        self.card_id = card_id
        self.account_id = account_id
        self.created_at = created_at
        self.payment = payment
